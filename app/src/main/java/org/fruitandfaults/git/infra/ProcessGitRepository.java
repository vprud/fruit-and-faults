package org.fruitandfaults.git.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

/** Owns bounded Git initialization and read-only repository probes, preserving cancellation. */
public final class ProcessGitRepository implements GitRepository {
  private final Duration timeout;
  private final int outputLimit;
  private final ProcessLauncher launcher;

  /** Uses a ten-second deadline, 64 KiB output cap, and the installed local Git executable. */
  public ProcessGitRepository() {
    this(Duration.ofSeconds(10), 65_536, ProcessGitRepository::launch);
  }

  ProcessGitRepository(Duration timeout, int outputLimit, ProcessLauncher launcher) {
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
    requireSafeRepositoryTree(gitDirectory);
    for (String redirected :
        List.of("commondir", "objects/info/alternates", "objects/info/http-alternates")) {
      if (Files.exists(gitDirectory.resolve(redirected), LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException(
            "Git repository redirects storage outside its local .git tree; preserve it and restore a self-contained repository.");
      }
    }
    requireLocalConfiguration(gitDirectory.resolve("config"));
    requireLocalConfiguration(gitDirectory.resolve("config.worktree"));
    String observed;
    try {
      observed =
          execute(
              List.of(
                  "git",
                  "-C",
                  normalized.toString(),
                  "rev-parse",
                  "--absolute-git-dir",
                  "--show-toplevel",
                  "--is-inside-work-tree"));
    } catch (GitInitializationException failed) {
      throw new IOException(
          "Existing Git repository could not be validated; preserve it and inspect its local state.",
          failed);
    }
    String expected = gitDirectory + "\n" + normalized + "\ntrue\n";
    if (!observed.replace("\r\n", "\n").equals(expected)) {
      throw new IOException(
          "Expected Git directory and worktree root to match the selected workspace exactly.");
    }
    requireSafeDirectory(gitDirectory);
    requireSafeRepositoryTree(gitDirectory);
  }

  private String execute(List<String> command) throws IOException {
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
    Future<String> captured = drain.submit(() -> readBounded(process.getInputStream()));
    try {
      process.getOutputStream().close();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw failure(GitInitializationException.Reason.TIMEOUT);
      }
      String output = captured.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new GitInitializationException(
            GitInitializationException.Reason.EXIT_FAILURE,
            OptionalInt.of(process.exitValue()),
            output);
      }
      return output;
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

  private String readBounded(InputStream input) throws IOException {
    try (input;
        ByteArrayOutputStream retained = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        int remaining = outputLimit - retained.size();
        if (remaining > 0) {
          retained.write(buffer, 0, Math.min(count, remaining));
        }
      }
      return retained.toString(StandardCharsets.UTF_8);
    }
  }

  private static Process launch(List<String> command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    // Git location/config environment variables must not redirect writes outside the chosen root.
    builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
    return builder.start();
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

  private static void requireLocalConfiguration(Path configuration) throws IOException {
    if (Files.notExists(configuration, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    byte[] bytes;
    try (InputStream input = Files.newInputStream(configuration, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(65_537);
    }
    if (bytes.length > 65_536) {
      throw new IOException("Git configuration exceeds the supported 64 KiB inspection limit.");
    }
    String text =
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    if (text.lines()
        .map(line -> line.stripLeading().toLowerCase(Locale.ROOT))
        .anyMatch(line -> line.startsWith("[include"))) {
      throw new IOException(
          "Workspace Git configuration includes external files; preserve it and restore self-contained configuration.");
    }
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
}
