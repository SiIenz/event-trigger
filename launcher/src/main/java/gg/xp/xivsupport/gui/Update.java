package gg.xp.xivsupport.gui;

import gg.xp.xivsupport.gui.util.CatchFatalErrorInUpdater;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// This one will NOT be launched with the full classpath - it NEEDS to be self-sufficient
// ...which is also why the code is complete shit, no external libraries.
public class Update {

	private static final String defaultUpdaterUrlTemplate = "https://xpdota.github.io/event-trigger/%s/v2/%s";
	private static final String defaultBranch = "stable";
	private final Consumer<String> logging;
	private final boolean updateTheUpdaterItself;
	private final boolean noop;
	private UpdaterLocation updateLocation;
	private static final String manifestFile = "manifest";
	private static final String propsOverrideFileName = "update.properties";
	private static final String updaterFilename = "triggevent-upd.exe";
	private static final String updaterFilenameBackup = "triggevent-upd.bak";
	private final File installDir;
	private final File depsDir;
	private final File propsOverride;
	private String rawAddonTemplates;

	private URI makeUrl(String filename) {
		try {
			return new URI(getUrlTemplate().formatted(getBranch(), filename));
		}
		catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	private URI makeAddonUrl(String urlTemplate, String filename) {
		try {
			return new URI(urlTemplate.formatted(filename));
		}
		catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	private record UpdaterLocation(
			String urlTemplate,
			String branch
	) {
	}

	private UpdaterLocation getUpdaterLocation() {
		if (updateLocation != null) {
			return updateLocation;
		}
		Properties props = new Properties();
		String updateUrlTemplate;
		String branch;
		if (propsOverride.exists()) {
			appendText("Properties file exists, loading it...");
			try {
				props.load(new FileInputStream(propsOverride));
			}
			catch (IOException e) {
				appendText("ERROR: Could not read properties!");
				appendText(e.toString());
				appendText(getStackTrace(e));
			}
			branch = props.getProperty("branch");
			if (branch == null) {
				appendText("Branch not specified in properties file, assuming default of " + defaultBranch);
				branch = defaultBranch;
			}
			updateUrlTemplate = props.getProperty("url_template");
			if (updateUrlTemplate == null) {
				appendText("URL not specified in properties file, assuming default of " + defaultUpdaterUrlTemplate);
				updateUrlTemplate = defaultUpdaterUrlTemplate;
			}
		}
		else {
			appendText("Properties file does not exist, creating one with defaults");
			props.setProperty("branch", defaultBranch);
			branch = defaultBranch;
			try {
				props.store(new FileOutputStream(propsOverride), "Created by updater");
			}
			catch (IOException e) {
				appendText("ERROR: Could not save properties!");
				appendText(e.toString());
				appendText(getStackTrace(e));
			}
			updateUrlTemplate = defaultUpdaterUrlTemplate;
		}
		appendText("Using branch: " + branch);
		appendText("Using URL template: " + updateUrlTemplate);
		UpdaterLocation updaterLocation = new UpdaterLocation(updateUrlTemplate, branch);
		this.updateLocation = updaterLocation;
		this.rawAddonTemplates = props.getProperty("addons", "");
		return updaterLocation;
	}

	private List<String> getAddonUrlTemplates() {
		return Arrays.stream(rawAddonTemplates.split("\n"))
				.filter(s -> !s.isBlank())
				.map(String::trim)
				.toList();
	}

	private String getUrlTemplate() {
		return getUpdaterLocation().urlTemplate();
	}

	private String getBranch() {
		return getUpdaterLocation().branch();
	}

	private Manifest getMainLocation() {
		return new Manifest("Main", Path.of("."), this::makeUrl) {
			@Override
			URI getManifestUri() {
				// Adding random junk to bypass cache
				return getUriForFile(manifestFile + "?q=" + System.currentTimeMillis() % 1000);
			}

			@Override
			boolean isMainManifest() {
				return true;
			}
		};
	}

	private List<Manifest> getAddonLocations() {
		return getAddonUrlTemplates()
				.stream()
				.map(line -> {
					// TODO: somewhere, there needs to be validation that someone didn't stick a : in the name of their addon
					String[] split = line.split(":", 2);
					if (split.length != 2) {
						throw new IllegalArgumentException("Malformed addon spec: " + line);
					}
					String name = split[0];
					String urlTemplate = split[1];
					return new Manifest(name, Path.of("addon/", name), file -> makeAddonUrl(urlTemplate, file));
				})
				.toList();
	}

	private List<Manifest> getAllManifests() {
		getUpdaterLocation();
		return Stream.concat(Stream.of(getMainLocation()), getAddonLocations().stream()).toList();
	}

	private Path getLocalFile(String name) {
		return Paths.get(installDir.toString(), name);
	}

	private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	private Update(Consumer<String> logging, boolean updateTheUpdaterItself, boolean onlyCheck) {
		this.logging = logging;
		this.updateTheUpdaterItself = updateTheUpdaterItself;
		this.noop = onlyCheck;
		String override = System.getProperty("triggevent-update-override-dir");
		if (override == null) {
			override = System.getenv("triggevent-update-override-dir");
		}
		if (override == null) {
			try {
				File jarLocation = new File(Update.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
				if (jarLocation.isFile()) {
					jarLocation = jarLocation.getParentFile();
				}
				// Special case for updating the updater itself
				if (jarLocation.getName().equals("deps") && updateTheUpdaterItself) {
					jarLocation = jarLocation.getParentFile();
				}
				this.installDir = jarLocation;
			}
			catch (URISyntaxException e) {
				throw new RuntimeException(e);
			}
		}
		else {
			this.installDir = new File(override);
			if (!installDir.isDirectory()) {
				throw new RuntimeException("Not a directory: " + installDir);
			}
		}
		depsDir = Paths.get(installDir.toString(), "deps").toFile();
		propsOverride = Paths.get(installDir.toString(), propsOverrideFileName).toFile();
		appendText("Install dir: " + installDir);
		appendText("Starting update check...");
	}

	private static class GraphicalUpdater {
		private final JFrame frame;
		private final JPanel content;
		private final JButton button;
		private final StringBuilder logText = new StringBuilder();
		private final JTextArea textArea;
		private final Update updater;

		GraphicalUpdater(boolean updateTheUpdater) {
			if (!updateTheUpdater) {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				}
				catch (Throwable e) {
					// Ignore
				}
			}
			frame = new JFrame("Triggevent Updater");
			frame.setSize(new Dimension(800, 500));
			frame.setLocationRelativeTo(null);
			content = new JPanel();
			content.setBorder(new EmptyBorder(10, 10, 10, 10));
			content.setLayout(new BorderLayout());
			frame.add(content);
			textArea = new JTextArea();
			textArea.setEditable(false);
			textArea.setLineWrap(true);
			textArea.setWrapStyleWord(true);
			textArea.setCaretPosition(0);
			textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JScrollPane scroll = new JScrollPane(textArea);
			scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
			scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
			content.add(scroll, BorderLayout.CENTER);
			button = new JButton("Wait");
			button.setPreferredSize(new Dimension(80, button.getPreferredSize().height));
			button.addActionListener(l -> System.exit(0));
			JPanel buttonHolder = new JPanel();
			buttonHolder.add(button);
			content.add(buttonHolder, BorderLayout.PAGE_END);
			frame.setVisible(true);
			frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
			button.setEnabled(false);
			updater = new Update(this::logText, updateTheUpdater, false);
		}

		public void run() {
			updater.doUpdateCheck();
			button.setText("Close");
			button.setEnabled(true);
		}

		void logText(String text) {
			logText.append(text).append('\n');
			textArea.setText(logText.toString());
			textArea.setCaretPosition(textArea.getDocument().getLength());
		}
	}


	private synchronized void appendText(String text) {
		logging.accept(text);
	}

	private static class Manifest {
		private final String name;
		private final Path dir;
		private final Function<String, URI> urlTemplate;

		private Manifest(String name, Path dir, Function<String, URI> urlTemplate) {
			this.name = name;
			this.dir = dir;
			this.urlTemplate = urlTemplate;
		}

		URI getManifestUri() {
			return getUriForFile(manifestFile);
		}

		URI getUriForFile(String file) {
			return urlTemplate.apply(file);
		}

		@Override
		public String toString() {
			return "Manifest{" +
					"name='" + name + '\'' +
					", dir=" + dir +
					", urlTemplate=" + urlTemplate +
					'}';
		}

		boolean isMainManifest() {
			return false;
		}

		//
//		@Override
//		public boolean equals(Object obj) {
//			if (obj == this) return true;
//			if (obj == null || obj.getClass() != this.getClass()) return false;
//			var that = (SingleManifest) obj;
//			return Objects.equals(this.name, that.name) &&
//					Objects.equals(this.urlTemplate, that.urlTemplate);
//		}
//
//		@Override
//		public int hashCode() {
//			return Objects.hash(name, urlTemplate);
//		}
//
//		@Override
//		public String toString() {
//			return "SingleManifest[" +
//					"name=" + name + ", " +
//					"urlTemplate=" + urlTemplate + ']';
//		}
//

	}

	private final class ExpectedFile {
		private final Manifest mft;
		private final String filePath;
		private final String hash;

		private ExpectedFile(Manifest mft, String filePath, String hash) {
			this.mft = mft;
			this.filePath = installDir.toPath().relativize(Path.of(filePath).toAbsolutePath()).toString();
			this.hash = hash;
		}

		public URI getUri() {
			return mft.getUriForFile(filePath);
		}

		public Manifest mft() {
			return mft;
		}

		public String filePath() {
			return filePath;
		}

		public String hash() {
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (ExpectedFile) obj;
			return Objects.equals(this.mft, that.mft) &&
					Objects.equals(this.filePath, that.filePath) &&
					Objects.equals(this.hash, that.hash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(mft, filePath, hash);
		}

		@Override
		public String toString() {
			return "ExpectedFile[" +
					"mft=" + mft + ", " +
					"filePath=" + filePath + ", " +
					"hash=" + hash + ']';
		}

	}

	private final class ActualFile {
		private final String filePath;
		private final String hash;

		private ActualFile(String filePath, String hash) {
			this.filePath = installDir.toPath().relativize(Path.of(filePath).toAbsolutePath()).toString();
			this.hash = hash;
		}

		public String filePath() {
			return filePath;
		}

		public String hash() {
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (ActualFile) obj;
			return Objects.equals(this.filePath, that.filePath) &&
					Objects.equals(this.hash, that.hash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(filePath, hash);
		}

		@Override
		public String toString() {
			return "ActualFile[" +
					"filePath=" + filePath + ", " +
					"hash=" + hash + ']';
		}

	}

	private boolean updateCheckSingleManifest(Manifest manifest) throws IOException, InterruptedException {
		if (manifest.isMainManifest()) {
			appendText("Checking for Triggevent updates...");
		}
		else {
			appendText("Checking for updates to addon '%s'".formatted(manifest.name));
		}
		URI uri = manifest.getManifestUri();
		HttpResponse<String> manifestResponse = client.send(HttpRequest.newBuilder().GET().uri(uri).build(), HttpResponse.BodyHandlers.ofString());
		if (manifestResponse.statusCode() != 200) {
			throw new RuntimeException("Bad response: %s: %s".formatted(manifestResponse.statusCode(), manifestResponse));
		}
		String body = manifestResponse.body();
		Map<String, ExpectedFile> expectedFilesForThisManifest = body.lines()
				.map(line -> line.split("\s+"))
				.map(s -> new ExpectedFile(manifest, Path.of(manifest.dir.toString(), s[1]).toString(), s[0]))
				.collect(Collectors.toMap(value -> value.filePath(), Function.identity()));
		// TODO: parallelize manifest + real downloads
		Map<String, ExpectedFile> expectedFiles = new HashMap<>();
		// TODO: delete this
		expectedFilesForThisManifest.forEach((k, newValue) -> {
			ExpectedFile oldValue = expectedFiles.putIfAbsent(k, newValue);
//			if (oldValue != null) {
//				appendText("Warning! More than one manifest claims file path %s!".formatted(k));
//				appendText("    1: %s".formatted(oldValue));
//				appendText("    2: %s".formatted(newValue));
//			}
		});
		Map<String, ActualFile> actualFiles = new HashMap<>();
		{
			appendText("Hashing Local Files...");
			{
				// TODO: turn this all into a "get covered files" method
				if (manifest.isMainManifest()) {
					File[] mainFiles = installDir.listFiles((dir, name) -> {
						String nameLower = name.toLowerCase(Locale.ROOT);
						return nameLower.endsWith(".exe") || nameLower.endsWith(".dll");
					});
					File[] depsFiles = depsDir.listFiles();
					if (mainFiles == null) {
						throw new RuntimeException("Error checking local main files. Try reinstalling.");
					}
					else if (depsFiles == null) {
						throw new RuntimeException("Error checking local deps files. Try reinstalling.");
					}
					for (File mainFile : mainFiles) {
						String name = mainFile.getName();
						ActualFile af = new ActualFile(name, md5sum(mainFile));
						actualFiles.put(af.filePath(), af);
					}
					for (File depsFile : depsFiles) {
						ActualFile af = new ActualFile(depsFile.toString(), md5sum(depsFile));
						actualFiles.put(af.filePath(), af);
					}
				}
				else {
					File[] addonFiles = manifest.dir.toFile().listFiles();
					if (addonFiles == null) {
						addonFiles = new File[0];
					}
					for (File addonFile : addonFiles) {
						String name = manifest.dir + addonFile.getName();
						ActualFile af = new ActualFile(name, md5sum(addonFile));
						actualFiles.put(af.filePath(), af);
					}
				}
			}
		}
		{
			List<String> updaterFiles = List.of(updaterFilename, updaterFilenameBackup);
			// For a no-op (i.e. just check for updates without applying anything), then we should check everything
			if (!noop && manifest.isMainManifest()) {
				// Updater will not be able to update itself
				if (updateTheUpdaterItself) {
					actualFiles.keySet().retainAll(updaterFiles);
					expectedFiles.keySet().retainAll(updaterFiles);
				}
				else {
					actualFiles.keySet().removeAll(updaterFiles);
					expectedFiles.keySet().removeAll(updaterFiles);
				}
			}
			List<String> allKeys = new ArrayList<>();
			allKeys.addAll(actualFiles.keySet());
			allKeys.addAll(expectedFiles.keySet());
			allKeys.sort(String::compareTo);
			Set<String> allKeysSet = new LinkedHashSet<>(allKeys);
			appendText("Calculating update...");
			allKeysSet.forEach(key -> {
				ActualFile actual = actualFiles.get(key);
				ExpectedFile expected = expectedFiles.get(key);
				String localHash = actual == null ? "null" : actual.hash();
				String remoteHash = expected == null ? "null" : expected.hash();
				String separator = localHash.equals(remoteHash) ? "==" : "->";
				appendText("%32s %s %32s %s".formatted(localHash, separator, remoteHash, key));
			});
			List<ActualFile> localFilesToDelete = new ArrayList<>();
			List<ExpectedFile> filesToDownload = new ArrayList<>();
			actualFiles.forEach((name, info) -> {
				String md5 = info.hash();
				ExpectedFile expectedFile = expectedFiles.get(name);
				String expectedHash = expectedFile == null ? null : expectedFile.hash();
				if (!md5.equals(expectedHash)) {
					localFilesToDelete.add(info);
				}
			});
			expectedFiles.forEach((name, info) -> {
				String md5 = info.hash();
				ActualFile actualFile = actualFiles.get(name);
				String actual = actualFile == null ? null : actualFile.hash();
				if (!md5.equals(actual)) {
					filesToDownload.add(info);
				}
			});
			if (!noop) {
				appendText(String.format("Updating %s files...", filesToDownload.size()));
				localFilesToDelete.forEach(info -> {
					boolean deleted;
					do {
						deleted = Paths.get(installDir.toString(), info.filePath()).toFile().delete();
						if (deleted) {
							return;
						}
						appendText("Could not delete file %s. Make sure the app is not running.".formatted(info.filePath()));
						try {
							Thread.sleep(5000);
						}
						catch (InterruptedException e) {
							// ignore
						}
					} while (true);
				});

				// TODO: why is both mkdirs() and mkdir() being used?
				depsDir.mkdirs();
				depsDir.mkdir();
				AtomicInteger downloaded = new AtomicInteger();
				filesToDownload.parallelStream().forEach((info) -> {
					HttpResponse.BodyHandler<Path> handler = HttpResponse.BodyHandlers.ofFile(getLocalFile(info.filePath()));
					try {
						client.send(HttpRequest.newBuilder().GET().uri(info.getUri()).build(), handler);
					}
					catch (IOException | InterruptedException e) {
						throw new RuntimeException(e);
					}
					appendText(String.format("Downloaded %s / %s files", downloaded.incrementAndGet(), filesToDownload.size()));
				});
				appendText("Update finished! %s files needed to be updated.".formatted(filesToDownload.size()));
			}
			// Chances of a file being deleted without anything else being touched are essentially zero
			return !filesToDownload.isEmpty();
		}
	}

	private boolean doUpdateCheck() {
		boolean anythingChanged = false;
		try {
			appendText("Beginning update check. If this hangs, freezes, or crashes, check that your AV is not interfering.");
			// Adding random junk to bypass cache
			List<Manifest> manifests = getAllManifests();
			for (Manifest manifest : manifests) {
				boolean result = updateCheckSingleManifest(manifest);
				if (result) {
					anythingChanged = true;
				}
			}
		}
		catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
		if (!updateTheUpdaterItself) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					if (isWindows()) {
						Runtime.getRuntime().exec(Paths.get(installDir.toString(), "triggevent.exe").toString());
					}
					else {
						Runtime.getRuntime().exec(new String[]{"sh", Paths.get(installDir.toString(), "triggevent.sh").toString()});
					}
				}
				catch (IOException e) {
					e.printStackTrace();
				}
			}));
		}
		return anythingChanged;
	}

	public static void main(String[] args) {
		CatchFatalErrorInUpdater.run(() -> {
			GraphicalUpdater gupdate = new GraphicalUpdater(false);
			try {
				Thread.sleep(1000);
			}
			catch (InterruptedException e) {
				// ignore
			}
			gupdate.run();
		});
	}

	@SuppressWarnings("unused")
	public static void updateTheUpdater() {
		new GraphicalUpdater(true).run();
	}

	@SuppressWarnings("unused")
	public static boolean justCheck(Consumer<String> logging) {
		return new Update(logging, true, true).doUpdateCheck();
	}

	private static String md5sum(File file) {
		try (FileInputStream fis = new FileInputStream(file)) {
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			try (DigestInputStream dis = new DigestInputStream(fis, md5)) {
				dis.readAllBytes();
			}
			byte[] md5sum = md5.digest();
			StringBuilder md5String = new StringBuilder();
			for (byte b : md5sum) {
				md5String.append(String.format("%02x", b & 0xff));
			}
			return md5String.toString();
		}
		catch (IOException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private static String getStackTrace(final Throwable throwable) {
		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw, true);
		throwable.printStackTrace(pw);
		return sw.getBuffer().toString();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
	}

}
