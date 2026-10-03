package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.fruitandfaults.workspace.application.WorkspaceLocationException;
import org.fruitandfaults.workspace.application.WorkspaceLocator;
import org.fruitandfaults.workspace.application.WorkspaceRoot;
import org.fruitandfaults.workspace.application.WorkspaceSetup;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.jspecify.annotations.Nullable;

/** Walks to the filesystem root and rejects incompatible, unsafe, or nested markers. */
public final class WalkingWorkspaceLocator implements WorkspaceLocator {
  private final WorkspaceMetadata expected;
  private final WorkspaceSetup setup;

  /**
   * Selects the exact supported workspace identity.
   *
   * @param expected installed course ID and supported layout
   * @param setup safe marker reader
   */
  public WalkingWorkspaceLocator(WorkspaceMetadata expected, WorkspaceSetup setup) {
    this.expected = Objects.requireNonNull(expected);
    this.setup = Objects.requireNonNull(setup);
  }

  @Override
  public WorkspaceRoot locate(Path current) throws IOException {
    List<WorkspaceRoot> found = new ArrayList<>();
    try {
      Path normalized = SafeWorkspaceSetup.safePath(current);
      if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Current directory does not exist.");
      }
      for (@Nullable Path candidate = normalized;
          candidate != null;
          candidate = candidate.getParent()) {
        var metadata = setup.loadMetadata(candidate);
        if (metadata.isPresent()) {
          if (!metadata.orElseThrow().equals(expected)) {
            throw new WorkspaceLocationException(
                WorkspaceLocationException.Reason.INCOMPATIBLE,
                "Workspace belongs to another course or layout; use the matching CLI installation.");
          }
          found.add(new WorkspaceRoot(candidate, metadata.orElseThrow()));
        }
      }
    } catch (WorkspaceLocationException failure) {
      throw failure;
    } catch (IOException unsafe) {
      throw new WorkspaceLocationException(
          WorkspaceLocationException.Reason.UNSAFE,
          "Workspace path or marker is unsafe or invalid; inspect its real directories and metadata.");
    }
    if (found.isEmpty()) {
      throw new WorkspaceLocationException(
          WorkspaceLocationException.Reason.NOT_FOUND,
          "No workspace found; run start or enter an initialized learner workspace.");
    }
    if (found.size() != 1) {
      throw new WorkspaceLocationException(
          WorkspaceLocationException.Reason.AMBIGUOUS,
          "Nested workspace markers are ambiguous; move the nested workspace to a separate location.");
    }
    return found.getFirst();
  }
}
