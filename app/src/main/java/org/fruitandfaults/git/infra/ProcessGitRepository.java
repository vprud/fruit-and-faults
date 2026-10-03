package org.fruitandfaults.git.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.validation.application.ProcessRequest;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;
import org.fruitandfaults.validation.infra.BoundedProcessRunner;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.jspecify.annotations.Nullable;

/** Owns bounded Git initialization and read-only repository probes, preserving cancellation. */
public final class ProcessGitRepository implements GitRepository {
  private final Duration timeout;
  private final int outputLimit;
  private final ProcessLauncher launcher;
  private final ProcessRunner statusRunner;
  private final ProcessRunner configRunner;

  /** Uses a ten-second deadline, 64 KiB output cap, and the installed local Git executable. */
  public ProcessGitRepository() {
    this(Duration.ofSeconds(10), 65_536, ProcessGitRepository::launch);
  }

  ProcessGitRepository(Duration timeout, int outputLimit, ProcessLauncher launcher) {
    this(
        timeout,
        outputLimit,
        launcher,
        new BoundedProcessRunner(ProcessGitRepository::configureGit));
  }

  ProcessGitRepository(
      Duration timeout, int outputLimit, ProcessLauncher launcher, ProcessRunner statusRunner) {
    this(timeout, outputLimit, launcher, statusRunner, new BoundedProcessRunner());
  }

  ProcessGitRepository(
      Duration timeout,
      int outputLimit,
      ProcessLauncher launcher,
      ProcessRunner statusRunner,
      ProcessRunner configRunner) {
    if (timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofMinutes(1)) > 0
        || outputLimit < 1
        || outputLimit > 1_048_576) {
      throw new IllegalArgumentException(
          "Expected a bounded positive Git deadline and output limit.");
    }
    this.timeout = timeout;
    this.outputLimit = outputLimit;
    this.launcher = Objects.requireNonNull(launcher);
    this.statusRunner = Objects.requireNonNull(statusRunner);
    this.configRunner = Objects.requireNonNull(configRunner);
  }

  @Override
  public void initialize(Path root) throws IOException {
    Path normalized = root.toAbsolutePath().normalize();
    requireSafeDirectory(normalized);
    if (Files.exists(normalized.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "Git directory already exists; preserve it and resume the initialized workspace.");
    }
    execute(List.of("git", "-C", normalized.toString(), "init", "--quiet", "--template="));
  }

  @Override
  public void requireInitialized(Path root) throws IOException {
    Path normalized = root.toAbsolutePath().normalize();
    Path gitDirectory = normalized.resolve(".git");
    requireSafeDirectory(gitDirectory);
    Path canonicalRoot = normalized.toRealPath();
    Path canonicalGitDirectory = gitDirectory.toRealPath();
    requireSafeRepositoryTree(gitDirectory);
    for (String redirected :
        List.of("commondir", "objects/info/alternates", "objects/info/http-alternates")) {
      if (Files.exists(gitDirectory.resolve(redirected), LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException(
            "Git repository redirects storage outside its local .git tree; preserve it and restore a self-contained repository.");
      }
    }
    requireLocalConfigurationsInWorker(normalized);
    requireNoExecutableFilters(normalized);
    CommandOutput repository;
    try {
      repository =
          probe(
              normalized,
              "rev-parse",
              "--absolute-git-dir",
              "--show-toplevel",
              "--is-inside-work-tree");
    } catch (GitInitializationException failed) {
      throw new IOException(
          "Existing Git repository could not be validated; preserve it and inspect its local state.",
          failed);
    }
    if (repository.exitCode() != 0 || repository.truncated()) {
      throw new IOException(
          "Existing Git repository could not be validated; preserve it and inspect its local state.");
    }
    requireMatchingRepository(repository.text(), canonicalRoot, canonicalGitDirectory);
    requireSafeDirectory(gitDirectory);
    requireSafeRepositoryTree(gitDirectory);
  }

  private void requireLocalConfigurationsInWorker(Path root) throws IOException {
    List<String> command;
    try {
      Path executable =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
      Path code =
          Path.of(
                  Objects.requireNonNull(
                          GitConfigWorker.class.getProtectionDomain().getCodeSource())
                      .getLocation()
                      .toURI())
              .toAbsolutePath()
              .normalize();
      command =
          List.of(
              executable.toString(),
              "-Xmx64m",
              "-XX:MaxMetaspaceSize=64m",
              "-cp",
              code.toString(),
              GitConfigWorker.class.getName());
    } catch (URISyntaxException | RuntimeException unavailable) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    }
    ProcessResult result = configRunner.run(new ProcessRequest(command, root, timeout, 1024));
    if (result instanceof ProcessResult.Interrupted) {
      Thread.currentThread().interrupt();
      throw failure(GitInitializationException.Reason.INTERRUPTED);
    }
    if (result instanceof ProcessResult.TimedOut) {
      throw failure(GitInitializationException.Reason.TIMEOUT);
    }
    if (result instanceof ProcessResult.Failed) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    }
    ProcessResult.Exited exited = (ProcessResult.Exited) result;
    if (exited.output().stdoutTruncated()
        || exited.output().stderrTruncated()
        || !exited.output().stdout().isBlank()
        || !exited.output().stderr().isBlank()) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    }
    if (exited.exitCode() == GitConfigWorker.SAFE) {
      return;
    }
    if (exited.exitCode() == GitConfigWorker.WORKSPACE_CONFLICT) {
      throw new IOException(
          "Workspace Git configuration could not be inspected safely; preserve it and remove external or executable directives.");
    }
    throw failure(GitInitializationException.Reason.UNAVAILABLE);
  }

  @Override
  public GitStatus status(Path root) throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw failure(GitInitializationException.Reason.INTERRUPTED);
    }
    try {
      requireInitialized(root);
    } catch (IOException failed) {
      if (failed.getCause() instanceof GitInitializationException typed) {
        throw typed;
      }
      throw failed;
    }
    Path normalized = root.toAbsolutePath().normalize();
    CommandOutput head = probe(normalized, "rev-parse", "--verify", "--quiet", "HEAD^{commit}");
    Optional<String> revision;
    if (head.exitCode() == 0) {
      String value = singleFact(head.text());
      if (!value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
        throw invalidOutput();
      }
      revision = Optional.of(value);
    } else if (head.exitCode() == 1 && head.text().isBlank()) {
      CommandOutput branch = probe(normalized, "symbolic-ref", "--quiet", "HEAD");
      if (branch.exitCode() != 0) {
        throw invalidOutput();
      }
      String name = singleFact(branch.text());
      if (!name.startsWith("refs/heads/")) {
        throw invalidOutput();
      }
      validateGitPath(name);
      CommandOutput absent = probe(normalized, "show-ref", "--verify", "--quiet", name);
      if (absent.exitCode() != 1 || !absent.text().isBlank()) {
        throw invalidOutput();
      }
      revision = Optional.empty();
    } else {
      throw invalidOutput();
    }
    CommandOutput changes =
        probe(
            normalized,
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.untrackedCache=false",
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none");
    if (changes.exitCode() != 0) {
      throw invalidOutput();
    }
    int[] counts = changeCounts(changes.text());
    CommandOutput origin = probe(normalized, "remote", "get-url", "origin");
    boolean hasOrigin;
    if (origin.exitCode() == 0) {
      singleFact(origin.text());
      hasOrigin = true;
    } else if (origin.exitCode() == 2) {
      hasOrigin = false;
    } else {
      throw invalidOutput();
    }
    CommandOutput upstream =
        probe(
            normalized, "rev-parse", "--verify", "--quiet", "--symbolic-full-name", "@{upstream}");
    boolean hasUpstream;
    if (upstream.exitCode() == 0) {
      String name = singleFact(upstream.text());
      if (!name.startsWith("refs/")) {
        throw invalidOutput();
      }
      validateGitPath(name);
      hasUpstream = true;
    } else if (upstream.exitCode() == 1 || upstream.exitCode() == 128) {
      hasUpstream = false;
    } else {
      throw invalidOutput();
    }
    return new GitStatus(revision, counts[0], counts[1], hasOrigin, hasUpstream);
  }

  private CommandOutput probe(Path root, String... arguments) throws IOException {
    List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
    command.addAll(List.of(arguments));
    ProcessResult result =
        statusRunner.run(new ProcessRequest(command, root, timeout, outputLimit));
    if (result instanceof ProcessResult.Interrupted) {
      Thread.currentThread().interrupt();
      throw failure(GitInitializationException.Reason.INTERRUPTED);
    }
    if (result instanceof ProcessResult.TimedOut) {
      throw failure(GitInitializationException.Reason.TIMEOUT);
    }
    if (!(result instanceof ProcessResult.Exited exited)) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    }
    if (exited.output().stdoutTruncated() || exited.output().stderrTruncated()) {
      throw invalidOutput();
    }
    return new CommandOutput(exited.exitCode(), exited.output().stdout(), false);
  }

  private static String singleFact(String output) throws IOException {
    List<String> lines = output.replace("\r\n", "\n").lines().toList();
    if (lines.size() != 1
        || lines.getFirst().isBlank()
        || lines
            .getFirst()
            .codePoints()
            .anyMatch(
                character ->
                    Character.getType(character) == Character.CONTROL
                        || Character.getType(character) == Character.FORMAT)) {
      throw invalidOutput();
    }
    return lines.getFirst();
  }

  private static int[] changeCounts(String output) throws IOException {
    int[] counts = {0, 0};
    if (output.isEmpty()) {
      return counts;
    }
    if (!output.endsWith("\0")) {
      throw invalidOutput();
    }
    String[] entries = output.split("\0", -1);
    for (int index = 0; index < entries.length - 1; index++) {
      String entry = entries[index];
      if (entry.length() < 4 || entry.charAt(2) != ' ') {
        throw invalidOutput();
      }
      String state = entry.substring(0, 2);
      if (state.equals("??")) {
        counts[1]++;
      } else if (!state.equals("  ")
          && state.chars().allMatch(value -> " MADRCUTU".indexOf(value) >= 0)) {
        counts[0]++;
      } else {
        throw invalidOutput();
      }
      validateGitPath(entry.substring(3));
      if (state.indexOf('R') >= 0 || state.indexOf('C') >= 0) {
        if (++index >= entries.length - 1) {
          throw invalidOutput();
        }
        validateGitPath(entries[index]);
      }
    }
    return counts;
  }

  private static void validateGitPath(String value) throws IOException {
    try {
      WorkspacePath.parse(value);
      if (value
          .codePoints()
          .anyMatch(character -> Character.getType(character) == Character.FORMAT)) {
        throw invalidOutput();
      }
    } catch (IllegalArgumentException invalid) {
      throw invalidOutput();
    }
  }

  private static IOException invalidOutput() {
    return new IOException(
        "Expected complete safe local Git facts; inspect the repository and retry status.");
  }

  private String execute(List<String> command) throws IOException {
    CommandOutput output = capture(command);
    if (output.exitCode() != 0) {
      throw new GitInitializationException(
          GitInitializationException.Reason.EXIT_FAILURE,
          OptionalInt.of(output.exitCode()),
          output.text());
    }
    if (output.truncated()) {
      throw invalidOutput();
    }
    return output.text();
  }

  private CommandOutput capture(List<String> command) throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw failure(GitInitializationException.Reason.INTERRUPTED);
    }
    Process process;
    try {
      process = launcher.start(command);
    } catch (IOException launchFailed) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    }
    ExecutorService drain =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "fruit-and-faults-git-output");
              thread.setDaemon(true);
              return thread;
            });
    Future<RetainedOutput> captured = drain.submit(() -> readBounded(process.getInputStream()));
    try {
      process.getOutputStream().close();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw failure(GitInitializationException.Reason.TIMEOUT);
      }
      RetainedOutput output = captured.get(1, TimeUnit.SECONDS);
      return new CommandOutput(process.exitValue(), output.text(), output.truncated());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw failure(GitInitializationException.Reason.INTERRUPTED);
    } catch (ExecutionException | TimeoutException failed) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    } finally {
      terminate(process);
      captured.cancel(true);
      drain.shutdownNow();
      process.getInputStream().close();
      process.getErrorStream().close();
    }
  }

  private RetainedOutput readBounded(InputStream input) throws IOException {
    try (input;
        ByteArrayOutputStream retained = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      boolean truncated = false;
      while ((count = input.read(buffer)) != -1) {
        int remaining = outputLimit - retained.size();
        truncated |= count > remaining;
        if (remaining > 0) {
          retained.write(buffer, 0, Math.min(count, remaining));
        }
      }
      return new RetainedOutput(retained.toString(StandardCharsets.UTF_8), truncated);
    }
  }

  private static Process launch(List<String> command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    configureGit(builder);
    return builder.start();
  }

  private static void configureGit(ProcessBuilder builder) {
    // Git location/config environment variables must not redirect writes outside the chosen root.
    builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder
        .environment()
        .put(
            "GIT_CONFIG_GLOBAL",
            System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    builder.environment().put("GIT_NO_LAZY_FETCH", "1");
    builder.environment().put("GIT_ALLOW_PROTOCOL", "");
    builder.environment().put("GIT_ATTR_NOSYSTEM", "1");
  }

  private static void requireSafeDirectory(Path root) throws IOException {
    Path current = Objects.requireNonNull(root.getRoot());
    for (Path segment : root) {
      current = current.resolve(segment);
      if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
        throw new IOException("Git access requires real directories without symlink ancestors.");
      }
    }
  }

  private static void requireMatchingRepository(String observed, Path root, Path gitDirectory)
      throws IOException {
    List<String> fields = observed.replace("\r\n", "\n").lines().toList();
    if (fields.size() != 3 || !fields.get(2).equals("true")) {
      throw new IOException("Expected three complete facts for a non-bare workspace repository.");
    }
    try {
      Path reportedGitDirectory = Path.of(fields.getFirst());
      Path reportedRoot = Path.of(fields.get(1));
      if (!reportedGitDirectory.isAbsolute() || !reportedRoot.isAbsolute()) {
        throw new IOException("Expected absolute Git directory and worktree root paths.");
      }
      requireSafeDirectory(reportedGitDirectory);
      requireSafeDirectory(reportedRoot);
      if (!Files.isSameFile(gitDirectory, reportedGitDirectory.toRealPath())
          || !Files.isSameFile(root, reportedRoot.toRealPath())) {
        throw new IOException(
            "Expected Git directory and worktree root to match the selected workspace exactly.");
      }
    } catch (InvalidPathException invalid) {
      throw new IOException(
          "Git reported invalid repository paths; preserve the workspace and inspect its repository.",
          invalid);
    }
  }

  private static void requireSafeRepositoryTree(Path gitDirectory) throws IOException {
    Files.walkFileTree(
        gitDirectory,
        Set.of(),
        32,
        new SimpleFileVisitor<>() {
          private int entries;

          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            requireSafeEntry(attributes);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            requireSafeEntry(attributes);
            if (!attributes.isRegularFile()) {
              throw new IOException(
                  "Git repository contains an unsupported entry or exceeds the directory depth limit.");
            }
            return FileVisitResult.CONTINUE;
          }

          private void requireSafeEntry(BasicFileAttributes attributes) throws IOException {
            if (++entries > 10_000
                || attributes.isSymbolicLink()
                || (!attributes.isDirectory() && !attributes.isRegularFile())) {
              throw new IOException(
                  "Git repository contains unsafe entries or exceeds the 10,000-entry inspection limit.");
            }
          }
        });
  }

  static void inspectLocalConfigurations(Path root, ConfigIdentityReader identityReader)
      throws IOException {
    Path gitDirectory = root.toAbsolutePath().normalize().resolve(".git");
    requireSafeDirectory(gitDirectory);
    requireLocalConfigurations(gitDirectory, identityReader);
  }

  private static void requireLocalConfigurations(
      Path gitDirectory, ConfigIdentityReader identityReader) throws IOException {
    BasicFileAttributes expected =
        Files.readAttributes(gitDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Object expectedKey = expected.fileKey();
    if (!expected.isDirectory() || expectedKey == null) {
      throw unsafeInspection();
    }
    try (DirectoryStream<Path> opened = Files.newDirectoryStream(gitDirectory)) {
      if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
        throw new IOException("Git configuration inspection requires secure directory handles.");
      }
      BasicFileAttributeView view = secure.getFileAttributeView(BasicFileAttributeView.class);
      if (view == null || !expectedKey.equals(view.readAttributes().fileKey())) {
        throw unsafeInspection();
      }
      requireLocalConfiguration(secure, Path.of("config"), identityReader);
      requireLocalConfiguration(secure, Path.of("config.worktree"), identityReader);
      if (!expectedKey.equals(view.readAttributes().fileKey())) {
        throw unsafeInspection();
      }
    }
    BasicFileAttributes retained =
        Files.readAttributes(gitDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!expectedKey.equals(retained.fileKey())) {
      throw unsafeInspection();
    }
  }

  private static void requireLocalConfiguration(
      SecureDirectoryStream<Path> directory, Path name, ConfigIdentityReader identityReader)
      throws IOException {
    ConfigSnapshot first = readLocalConfiguration(directory, name, identityReader);
    ConfigSnapshot second = readLocalConfiguration(directory, name, identityReader);
    if (first.present() != second.present()) {
      throw unsafeInspection();
    }
    if (!first.present()) {
      return;
    }
    if (!Objects.equals(first.key(), second.key())
        || first.size() != second.size()
        || !Arrays.equals(first.bytes(), second.bytes())) {
      throw unsafeInspection();
    }
    requireSafeConfigurationBytes(first.bytes());
  }

  private static ConfigSnapshot readLocalConfiguration(
      SecureDirectoryStream<Path> directory, Path name, ConfigIdentityReader identityReader)
      throws IOException {
    BasicFileAttributes before;
    try {
      before =
          directory
              .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
    } catch (java.nio.file.NoSuchFileException absent) {
      return ConfigSnapshot.absent();
    }
    Object key = identityReader.key(name, before);
    if (!before.isRegularFile() || key == null || before.size() > 65_536) {
      throw new IOException("Git configuration is not a bounded regular file.");
    }
    byte[] bytes;
    try (SeekableByteChannel channel =
        directory.newByteChannel(
            name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      ByteBuffer buffer = ByteBuffer.allocate((int) before.size() + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
      bytes = Arrays.copyOf(buffer.array(), buffer.position());
      if (bytes.length != before.size() || channel.size() != before.size()) {
        throw unsafeInspection();
      }
    }
    BasicFileAttributes after =
        directory
            .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .readAttributes();
    if (!key.equals(identityReader.key(name, after)) || after.size() != before.size()) {
      throw unsafeInspection();
    }
    return new ConfigSnapshot(true, key, before.size(), bytes);
  }

  private static void requireSafeConfigurationBytes(byte[] bytes) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    String lowered = asciiLower(text);
    if (text.indexOf('\ufeff') >= 0
        || text.indexOf('\0') >= 0
        || lowered.contains("filter")
        || lowered.contains("include")
        || lowered.contains("attributesfile")
        || lowered.contains("worktreeconfig")
        || text.contains("\\\n")
        || text.contains("\\\r")) {
      throw new IOException(
          "Workspace Git configuration contains unsupported executable or external directives.");
    }
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if ((value < 0x20 && value != '\n' && value != '\r' && value != '\t') || value == 0x7f) {
        throw new IOException("Workspace Git configuration contains unsupported control bytes.");
      }
    }
  }

  private static String asciiLower(String value) {
    StringBuilder lowered = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      lowered.append(
          character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
    }
    return lowered.toString();
  }

  private static void requireNoExecutableFilters(Path root) throws IOException {
    Files.walkFileTree(
        root,
        Set.of(),
        32,
        new SimpleFileVisitor<>() {
          private int entries;

          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            requireSafeEntry(attributes);
            Path relative = root.relativize(directory);
            if (relative.getNameCount() > 0
                && directory.getFileName().toString().equalsIgnoreCase(".git")) {
              if (relative.getNameCount() > 1
                  || !directory.getFileName().toString().equals(".git")) {
                throw unsafeInspection();
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            requireSafeEntry(attributes);
            if (!attributes.isRegularFile()) {
              throw unsafeInspection();
            }
            String name = file.getFileName().toString();
            if (name.equalsIgnoreCase(".git")) {
              throw unsafeInspection();
            }
            if (name.equalsIgnoreCase(".gitattributes") && !name.equals(".gitattributes")) {
              throw unsafeInspection();
            }
            if ((name.equalsIgnoreCase("config") || name.equalsIgnoreCase("config.worktree"))
                && containsGitSegment(root.relativize(file))
                && !(name.equals("config") || name.equals("config.worktree"))) {
              throw unsafeInspection();
            }
            if (isAttributesFile(root, file)) {
              requireNoFilterAttribute(file);
            }
            return FileVisitResult.CONTINUE;
          }

          private void requireSafeEntry(BasicFileAttributes attributes) throws IOException {
            if (++entries > 10_000 || attributes.isSymbolicLink()) {
              throw unsafeInspection();
            }
          }
        });
  }

  private static boolean isAttributesFile(Path root, Path file) {
    if (file.getFileName().toString().equals(".gitattributes")) {
      return true;
    }
    Path relative = root.relativize(file);
    return file.getFileName().toString().equals("attributes")
        && relative.getNameCount() >= 3
        && relative.getName(relative.getNameCount() - 2).toString().equals("info")
        && containsGitSegment(relative);
  }

  private static boolean containsGitSegment(Path relative) {
    for (Path segment : relative) {
      if (segment.toString().equals(".git")) {
        return true;
      }
    }
    return false;
  }

  private static void requireNoFilterAttribute(Path attributes) throws IOException {
    byte[] bytes;
    try (InputStream input = Files.newInputStream(attributes, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(65_537);
    }
    if (bytes.length > 65_536) {
      throw unsafeInspection();
    }
    String text =
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    if (text.lines()
        .map(String::stripLeading)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .anyMatch(line -> line.matches(".*(?:^|\\s)(?:-|!|\\?)?filter(?:=\\S+)?(?:\\s|$).*$"))) {
      throw new IOException(
          "Workspace attributes activate executable content filters; remove those attributes before retrying.");
    }
  }

  private static IOException unsafeInspection() {
    return new IOException(
        "Workspace Git safety inspection encountered an unsafe entry or exceeded its bounded limits.");
  }

  private static void terminate(Process process) {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(1, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private static GitInitializationException failure(GitInitializationException.Reason reason) {
    return new GitInitializationException(reason, OptionalInt.empty(), "");
  }

  @FunctionalInterface
  interface ProcessLauncher {
    Process start(List<String> command) throws IOException;
  }

  @FunctionalInterface
  interface ConfigIdentityReader {
    @Nullable Object key(Path name, BasicFileAttributes attributes) throws IOException;
  }

  private record RetainedOutput(String text, boolean truncated) {}

  private record CommandOutput(int exitCode, String text, boolean truncated) {}

  private static final class ConfigSnapshot {
    private final boolean present;
    private final @Nullable Object key;
    private final long size;
    private final byte[] bytes;

    private ConfigSnapshot(boolean present, @Nullable Object key, long size, byte[] bytes) {
      this.present = present;
      this.key = key;
      this.size = size;
      this.bytes = bytes;
    }

    private static ConfigSnapshot absent() {
      return new ConfigSnapshot(false, null, 0, new byte[0]);
    }

    private boolean present() {
      return present;
    }

    private @Nullable Object key() {
      return key;
    }

    private long size() {
      return size;
    }

    private byte[] bytes() {
      return bytes;
    }
  }
}
