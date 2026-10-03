package org.fruitandfaults.validation.infra;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.jspecify.annotations.Nullable;

/**
 * Loads main game bytecode on demand through bounded anchored reads. Each validation owns a fresh,
 * closeable loader with only the platform loader as parent; app, test, and dependency classpaths
 * are excluded. Public validation runs learner bytecode only in an owned bounded worker JVM.
 */
public final class CompiledGameLoader {
  private static final String PACKAGE = "org.fruitandfaults.game.";
  private final WorkspaceFiles files;
  private final ProcessRunner runner;
  private final ExecutionMode mode;

  /** Uses the existing safe workspace boundary inside each owned bounded validation worker. */
  public CompiledGameLoader() {
    this(new BoundedProcessRunner());
  }

  /**
   * Selects a bounded worker execution boundary.
   *
   * @param runner owner of each worker process and its deadline
   */
  public CompiledGameLoader(ProcessRunner runner) {
    this(new SafeWorkspaceFiles(), runner, ExecutionMode.FORKED);
  }

  private CompiledGameLoader(WorkspaceFiles files, ProcessRunner runner, ExecutionMode mode) {
    this.files = Objects.requireNonNull(files);
    this.runner = Objects.requireNonNull(runner);
    this.mode = mode;
  }

  static CompiledGameLoader workerLoader(WorkspaceFiles files) {
    return new CompiledGameLoader(files, new BoundedProcessRunner(), ExecutionMode.WORKER);
  }

  CheckOutcome validate(Path root, String criterion, String expected, PublicCheck check) {
    if (mode == ExecutionMode.FORKED) {
      return validateWorker(root, criterion, expected);
    }
    try (Game game = new Game(root.toAbsolutePath().normalize(), files)) {
      try {
        check.verify(game);
        return new CheckOutcome.Passed(
            List.of(
                new Diagnostic(
                    expected,
                    "The required public behavior was observed.",
                    "Continue with the lesson's next step.")));
      } catch (ReflectiveOperationException | RuntimeException | Error contractFailure) {
        // Never include learner exception messages, object strings, or stack traces in feedback.
        return failure(
            game.unsafeRead ? FailureCategory.WORKSPACE_CONFLICT : FailureCategory.INCOMPLETE_WORK,
            expected,
            game.unsafeRead
                ? "Compiled main classes could not be read safely."
                : "The required public contract was unavailable, threw an exception, or returned an unexpected result.",
            game.unsafeRead
                ? "Replace unsafe build paths with regular workspace directories and rerun check."
                : "Compare the public methods and visible tests with the lesson contract, rebuild, and run check again.");
      }
    } catch (IOException closeFailure) {
      return failure(
          FailureCategory.INTERNAL_ERROR,
          "Closed validation classloader resources.",
          "The validation classloader could not be closed.",
          "Retry check after inspecting the local runtime.");
    }
  }

  private CheckOutcome validateWorker(Path root, String criterion, String expected) {
    WorkerProcess.Reply reply =
        WorkerProcess.run(runner, root, ValidationWorker.class, List.of(criterion));
    return switch (reply.result()) {
      case ProcessResult.TimedOut timed ->
          WorkerProcess.withCleanup(
              failure(
                  FailureCategory.TIMEOUT,
                  expected,
                  "Public-behavior validation exceeded its deadline.",
                  "Inspect nonterminating game methods, then run check again."),
              timed.cleanup());
      case ProcessResult.Interrupted interrupted ->
          WorkerProcess.withCleanup(
              failure(
                  FailureCategory.INTERRUPTED,
                  expected,
                  "Public-behavior validation was interrupted.",
                  "Run check again when ready to continue."),
              interrupted.cleanup());
      case ProcessResult.Failed ignored ->
          failure(
              FailureCategory.INTERNAL_ERROR,
              expected,
              "The isolated validation worker could not be started or safely supervised.",
              "Check the local Java runtime and remaining processes, then retry.");
      case ProcessResult.Exited ignored -> workerOutcome(reply.payload().orElse(""), expected);
    };
  }

  private static CheckOutcome workerOutcome(String token, String expected) {
    if (token.equals("PASSED")) {
      return new CheckOutcome.Passed(
          List.of(
              new Diagnostic(
                  expected,
                  "The required public behavior was observed.",
                  "Continue with the lesson's next step.")));
    }
    if (token.equals("WORKSPACE_CONFLICT")) {
      return failure(
          FailureCategory.WORKSPACE_CONFLICT,
          expected,
          "Compiled main classes could not be read safely.",
          "Replace unsafe build paths with regular workspace directories and rerun check.");
    }
    if (token.equals("INTERNAL_ERROR")) {
      return failure(
          FailureCategory.INTERNAL_ERROR,
          expected,
          "The isolated validation worker could not finish reliably.",
          "Check the installed course and runtime, then retry check.");
    }
    return failure(
        FailureCategory.INCOMPLETE_WORK,
        expected,
        "The public contract was unavailable, threw an exception, returned an unexpected result, or terminated validation.",
        "Compare public methods and visible tests with the lesson contract, rebuild, and run check again.");
  }

  private static CheckOutcome.Failed failure(
      FailureCategory category, String expected, String observed, String nextAction) {
    return new CheckOutcome.Failed(
        category, List.of(new Diagnostic(expected, observed, nextAction)));
  }

  @FunctionalInterface
  interface PublicCheck {
    void verify(Game game) throws ReflectiveOperationException;
  }

  private enum ExecutionMode {
    FORKED,
    WORKER
  }

  static final class Game extends URLClassLoader {
    private final Path root;
    private final WorkspaceFiles files;
    private boolean unsafeRead;
    private int loadedClasses;

    private Game(Path root, WorkspaceFiles files) {
      super(new URL[0], ClassLoader.getPlatformClassLoader());
      this.root = root;
      this.files = files;
    }

    @Override
    protected Class<?> findClass(@Nullable String name) throws ClassNotFoundException {
      if (name == null || !isBinaryName(name) || ++loadedClasses > 256) {
        throw new ClassNotFoundException("Expected a bounded main game class.");
      }
      WorkspacePath path =
          WorkspacePath.parse("build/classes/java/main/" + name.replace('.', '/') + ".class");
      byte[] bytes;
      try {
        bytes =
            files
                .read(root, path)
                .orElseThrow(() -> new ClassNotFoundException("Main game class is missing."));
      } catch (IOException unsafe) {
        unsafeRead = true;
        throw new ClassNotFoundException("Main game class could not be read safely.", unsafe);
      }
      return defineClass(name, bytes, 0, bytes.length);
    }

    private static boolean isBinaryName(String name) {
      if (name.isEmpty() || name.length() > 4096) {
        return false;
      }
      for (String segment : name.split("\\.", -1)) {
        if (segment.isEmpty()
            || !Character.isJavaIdentifierStart(segment.codePointAt(0))
            || !segment.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)) {
          return false;
        }
      }
      return true;
    }

    Class<?> type(String simpleName) throws ClassNotFoundException {
      Class<?> type = Class.forName(PACKAGE + simpleName, true, this);
      require(Modifier.isPublic(type.getModifiers()));
      return type;
    }

    Object construct(Class<?> type, Class<?>[] parameters, Object... arguments)
        throws ReflectiveOperationException {
      Constructor<?> constructor = type.getConstructor(parameters);
      return constructor.newInstance(arguments);
    }

    Method method(
        Class<?> type, String name, Class<?> returns, boolean isStatic, Class<?>... parameters)
        throws NoSuchMethodException {
      Method method = type.getMethod(name, parameters);
      require(
          method.getReturnType().equals(returns)
              && Modifier.isStatic(method.getModifiers()) == isStatic);
      return method;
    }

    Object call(Method method, @Nullable Object target, Object... arguments)
        throws ReflectiveOperationException {
      Object result = method.invoke(target, arguments);
      require(result != null);
      return Objects.requireNonNull(result);
    }

    Object direction(Class<?> direction, String name) throws ReflectiveOperationException {
      require(direction.isEnum());
      for (Object constant : Objects.requireNonNull(direction.getEnumConstants())) {
        if (((Enum<?>) constant).name().equals(name)) {
          return constant;
        }
      }
      throw new NoSuchFieldException("Expected public direction constant.");
    }

    Object coordinate(int x, int y) throws ReflectiveOperationException {
      return construct(type("Coordinate"), new Class<?>[] {int.class, int.class}, x, y);
    }

    void coordinateEquals(Object coordinate, int x, int y) throws ReflectiveOperationException {
      Class<?> type = type("Coordinate");
      require(call(method(type, "x", int.class, false), coordinate).equals(x));
      require(call(method(type, "y", int.class, false), coordinate).equals(y));
    }

    static void require(boolean matches) {
      if (!matches) {
        throw new IllegalArgumentException("Public contract did not match.");
      }
    }
  }
}
