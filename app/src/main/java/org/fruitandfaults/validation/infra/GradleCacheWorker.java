package org.fruitandfaults.validation.infra;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipInputStream;

import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.jspecify.annotations.Nullable;

/**
 * Inspect-only worker for the exact wrapper cache; it never invokes Gradle or performs a download.
 */
public final class GradleCacheWorker {
  private GradleCacheWorker() {}

  /**
   * Validates bounded metadata and a single URL-derived distribution cache with anchored handles.
   *
   * @param arguments one explicit native Gradle user home path
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      return;
    }
    byte[] secret;
    try {
      secret = WorkerProtocol.consumeSecret();
    } catch (IOException invalidInput) {
      return;
    }
    String payload;
    try {
      verify(Path.of(""), Path.of(arguments[0]));
      payload = "READY";
    } catch (NoSuchFileException missing) {
      payload = "MISSING_ARTIFACT";
    } catch (IOException | RuntimeException | Error invalid) {
      payload = "INTERNAL_ERROR";
    }
    System.out.println(WorkerProtocol.frame(secret, payload));
    java.util.Arrays.fill(secret, (byte) 0);
  }

  static void verify(Path workspaceRoot, Path home) throws IOException {
    var files = new SafeWorkspaceFiles();
    byte[] bytes =
        files
            .read(workspaceRoot, WorkspacePath.parse("gradle/wrapper/gradle-wrapper.properties"))
            .orElseThrow(() -> new NoSuchFileException("Wrapper metadata missing."));
    if (bytes.length > 16_384) {
      throw new IOException("Wrapper metadata exceeds its bounded budget.");
    }
    Properties properties = new UniqueProperties();
    properties.load(new ByteArrayInputStream(bytes));
    requireProperty(properties, "distributionBase", "GRADLE_USER_HOME");
    requireProperty(properties, "distributionPath", "wrapper/dists");
    requireProperty(properties, "zipStoreBase", "GRADLE_USER_HOME");
    requireProperty(properties, "zipStorePath", "wrapper/dists");
    URI url = URI.create(properties.getProperty("distributionUrl", ""));
    if (!url.isAbsolute()
        || !"https".equals(url.getScheme())
        || url.getHost() == null
        || url.getPath() == null
        || !url.normalize().equals(url)) {
      throw new IOException("Unsupported wrapper distribution URI.");
    }
    String archive = Objects.requireNonNull(Path.of(url.getPath()).getFileName()).toString();
    if (!archive.matches("gradle-[0-9][a-zA-Z0-9.-]{0,100}-(bin|all)\\.zip")) {
      throw new IOException("Unsupported wrapper archive.");
    }
    String distribution = archive.substring(0, archive.length() - 4);
    String payload = distribution.substring(0, distribution.lastIndexOf('-'));
    String selected = "wrapper/dists/" + distribution + "/" + cacheKey(url);
    Map<Path, Object> ancestors = realAncestors(home.toAbsolutePath().normalize());
    try (DirectoryStream<Path> opened = Files.newDirectoryStream(home);
        var snapshot = new CacheSnapshot(home, ancestors, opened)) {
      snapshot.verifySelected(selected, payload);
      byte[] marker =
          files
              .read(home, WorkspacePath.parse(selected + "/" + archive + ".ok"))
              .orElseThrow(() -> new NoSuchFileException("Wrapper completion marker missing."));
      if (marker.length != 0) {
        throw new IOException("Malformed completion marker.");
      }
      String launcher =
          selected
              + "/"
              + payload
              + "/lib/gradle-launcher-"
              + payload.substring("gradle-".length())
              + ".jar";
      byte[] jar =
          files
              .read(home, WorkspacePath.parse(launcher))
              .orElseThrow(() -> new NoSuchFileException("Wrapper launcher missing."));
      String cliName = "gradle-gradle-cli-main-" + payload.substring("gradle-".length()) + ".jar";
      var delegated = verifyLauncher(jar, cliName);
      if (delegated.isPresent()) {
        byte[] cli =
            files
                .read(home, WorkspacePath.parse(selected + "/" + payload + "/lib/" + cliName))
                .orElseThrow(() -> new NoSuchFileException("Wrapper CLI main payload missing."));
        verifyLauncher(cli, "");
      }
      snapshot.recheck();
    }
  }

  private static void requireProperty(Properties properties, String key, String supported)
      throws IOException {
    if (!properties.getProperty(key, supported).equals(supported)) {
      throw new IOException("Unsupported wrapper cache location.");
    }
  }

  private static String cacheKey(URI url) throws IOException {
    try {
      URI safe =
          new URI(
              url.getScheme(),
              null,
              url.getHost(),
              url.getPort(),
              url.getPath(),
              url.getQuery(),
              url.getFragment());
      // MD5/base36 is Gradle's cache-address algorithm, not a content-integrity checksum.
      return new BigInteger(
              1,
              MessageDigest.getInstance("MD5")
                  .digest(safe.toASCIIString().getBytes(StandardCharsets.UTF_8)))
          .toString(36);
    } catch (URISyntaxException | NoSuchAlgorithmException invalid) {
      throw new IOException("Wrapper cache identity unavailable.", invalid);
    }
  }

  private static java.util.Optional<String> verifyLauncher(byte[] bytes, String expectedDelegate)
      throws IOException {
    int total = 0;
    @Nullable String delegate = null;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (int entries = 0; entries < 2048; entries++) {
        var entry = zip.getNextEntry();
        if (entry == null) {
          break;
        }
        byte[] content = zip.readNBytes(65_537);
        total += content.length;
        if (content.length > 65_536 || total > 16_777_216) {
          throw new IOException("Launcher archive exceeds its bounded budget.");
        }
        if (entry.getName().equals("org/gradle/launcher/GradleMain.class") && content.length > 0) {
          return java.util.Optional.empty();
        }
        if (entry.getName().equals("META-INF/MANIFEST.MF")) {
          delegate =
              new java.util.jar.Manifest(new ByteArrayInputStream(content))
                  .getMainAttributes()
                  .getValue("Class-Path");
        }
      }
    }
    if (delegate != null && !delegate.isBlank() && delegate.equals(expectedDelegate)) {
      return java.util.Optional.of(delegate);
    }
    throw new IOException("Malformed Gradle launcher payload.");
  }

  private static Map<Path, Object> realAncestors(Path home) throws IOException {
    Map<Path, Object> ancestors = new LinkedHashMap<>();
    Path current = Objects.requireNonNull(home.getRoot());
    ancestors.put(
        current,
        directoryKey(
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
    for (Path segment : home) {
      current = current.resolve(segment);
      ancestors.put(
          current,
          directoryKey(
              Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
    }
    return ancestors;
  }

  private static Object directoryKey(BasicFileAttributes attributes) throws IOException {
    if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null) {
      throw new IOException("Cache directory has unsafe type or identity.");
    }
    return attributes.fileKey();
  }

  private static final class CacheSnapshot implements AutoCloseable {
    private final Path home;
    private final Map<Path, Object> ancestors;
    private final SecureDirectoryStream<Path> root;
    private final ArrayList<SecureDirectoryStream<Path>> handles = new ArrayList<>();
    private final ArrayList<EntryIdentity> identities = new ArrayList<>();
    private int entries;

    CacheSnapshot(Path home, Map<Path, Object> ancestors, DirectoryStream<Path> opened)
        throws IOException {
      this.home = home;
      this.ancestors = ancestors;
      if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
        throw new IOException("Cache provider lacks anchored directory access.");
      }
      this.root = secure;
    }

    void verifySelected(String selected, String expectedPayload) throws IOException {
      SecureDirectoryStream<Path> secure = root;
      if (!directoryKey(secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes())
          .equals(ancestors.get(home))) {
        throw new IOException("Cache root identity changed.");
      }
      for (Path segment : home.getFileSystem().getPath(selected)) {
        secure = descend(secure, segment);
      }
      scan(secure, "", expectedPayload, 0);
    }

    private SecureDirectoryStream<Path> descend(SecureDirectoryStream<Path> parent, Path name)
        throws IOException {
      Object key = directoryKey(attributes(parent, name));
      SecureDirectoryStream<Path> child =
          parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
      handles.add(child);
      identities.add(new EntryIdentity(parent, name, key));
      if (!key.equals(
          directoryKey(
              child.getFileAttributeView(BasicFileAttributeView.class).readAttributes()))) {
        throw new IOException("Cache directory identity changed.");
      }
      return child;
    }

    private void scan(
        SecureDirectoryStream<Path> directory, String relative, String payload, int depth)
        throws IOException {
      if (depth > 12) {
        throw new IOException("Cache payload exceeds its bounded depth.");
      }
      int directories = 0;
      int launchers = 0;
      for (Path entry : directory) {
        if (++entries > 5000) {
          throw new IOException("Cache payload exceeds its bounded entry count.");
        }
        Path name = Objects.requireNonNull(entry.getFileName());
        BasicFileAttributes attributes = attributes(directory, name);
        if (attributes.isSymbolicLink()
            || attributes.fileKey() == null
            || (!attributes.isRegularFile() && !attributes.isDirectory())) {
          throw new IOException("Unsafe cache payload entry.");
        }
        identities.add(new EntryIdentity(directory, name, attributes.fileKey()));
        if (relative.equals(payload + "/lib")
            && name.toString().matches("gradle-launcher-.+\\.jar")) {
          launchers++;
        }
        if (attributes.isDirectory()) {
          directories++;
          if (depth == 0 && !name.toString().equals(payload)) {
            throw new IOException("Ambiguous Gradle cache payload.");
          }
          var child = descend(directory, name);
          scan(
              child,
              relative.isEmpty() ? name.toString() : relative + "/" + name,
              payload,
              depth + 1);
        }
      }
      if ((depth == 0 && directories != 1)
          || (relative.equals(payload + "/lib") && launchers != 1)) {
        throw new IOException("Malformed or ambiguous Gradle cache payload.");
      }
    }

    void recheck() throws IOException {
      for (var entry : identities) {
        var current = attributes(entry.parent(), entry.name());
        if (current.isSymbolicLink() || !entry.key().equals(current.fileKey())) {
          throw new IOException("Cache entry identity changed during verification.");
        }
      }
      for (var entry : ancestors.entrySet()) {
        if (!entry
            .getValue()
            .equals(
                directoryKey(
                    Files.readAttributes(
                        entry.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))) {
          throw new IOException("Cache ancestor identity changed during verification.");
        }
      }
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      for (var handle : handles.reversed()) {
        try {
          handle.close();
        } catch (IOException failed) {
          failure = failed;
        }
      }
      if (failure != null) {
        throw failure;
      }
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> parent, Path name)
        throws IOException {
      var view =
          parent.getFileAttributeView(
              name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view == null) {
        throw new IOException("Cache provider lacks entry attributes.");
      }
      return view.readAttributes();
    }

    private record EntryIdentity(SecureDirectoryStream<Path> parent, Path name, Object key) {}
  }

  private static final class UniqueProperties extends Properties {
    @Override
    public synchronized @Nullable Object put(Object key, Object value) {
      if (containsKey(key) || size() >= 64) {
        throw new IllegalArgumentException("Ambiguous or excessive wrapper properties.");
      }
      return super.put(key, value);
    }
  }
}
