package org.fruitandfaults.validation.infra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonAsset;
import org.fruitandfaults.validation.application.ArtifactInspector;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/** Checks installed declarations against manifest facts and bounded anchored learner-file reads. */
public final class ManifestArtifactInspector implements ArtifactInspector {
  private final WorkspaceFiles files;
  private final CourseAssets assets;

  /**
   * Uses the existing workspace safety boundary and installed baseline bytes.
   *
   * @param files bounded anchored filesystem access
   * @param assets read-only installed course assets
   */
  public ManifestArtifactInspector(WorkspaceFiles files, CourseAssets assets) {
    this.files = Objects.requireNonNull(files);
    this.assets = Objects.requireNonNull(assets);
  }

  @Override
  public CheckOutcome inspect(Path root, List<Lesson> openedLessons, ManagedFiles manifest) {
    List<Diagnostic> observations = new ArrayList<>();
    Map<WorkspacePath, LessonAsset> declarations = new LinkedHashMap<>();
    Set<WorkspacePath> paths = new LinkedHashSet<>();
    Map<WorkspacePath, ManagedFile> expected = new LinkedHashMap<>();
    for (Lesson lesson : openedLessons) {
      for (LessonAsset asset : lesson.assets()) {
        WorkspacePath path = WorkspacePath.parse(asset.relativePath());
        declarations.put(path, asset);
        paths.add(path);
        expected.put(
            path, new ManagedFile(path, asset.id(), asset.sha256(), lesson.id(), asset.policy()));
      }
      lesson.expectedArtifacts().forEach(path -> paths.add(WorkspacePath.parse(path)));
    }
    FailureCategory category = null;
    for (WorkspacePath path : paths) {
      String label = display(path.value());
      ManagedFile baseline = expected.get(path);
      if (path.isToolMetadata()
          || (baseline != null && !manifest.find(path).equals(Optional.of(baseline)))) {
        observations.add(
            new Diagnostic(
                "Matching disclosed ownership for " + label + ".",
                "The ownership manifest is missing or conflicts with the installed declaration.",
                "Inspect the manifest and restore its recorded course facts explicitly."));
        category = FailureCategory.WORKSPACE_CONFLICT;
        continue;
      }
      try {
        Optional<byte[]> content = files.read(root, path);
        if (content.isEmpty()) {
          observations.add(
              new Diagnostic(
                  "Required artifact " + label + ".",
                  "The artifact is missing.",
                  "Restore the missing artifact explicitly, then run check again."));
          if (category != FailureCategory.WORKSPACE_CONFLICT) {
            category = FailureCategory.MISSING_ARTIFACT;
          }
          continue;
        }
        if (baseline == null) {
          continue;
        }
        boolean changed = !sha256(content.orElseThrow()).equals(baseline.sha256());
        switch (baseline.policy()) {
          case IMMUTABLE_CHECK -> {
            if (changed) {
              observations.add(
                  new Diagnostic(
                      "Exact disclosed visible-check bytes for " + label + ".",
                      "The immutable visible check was modified.",
                      "Inspect the diff and restore the disclosed check explicitly before retrying."));
              category = FailureCategory.WORKSPACE_CONFLICT;
            }
          }
          case EDITABLE_TEMPLATE -> {
            if (!changed) {
              observations.add(
                  new Diagnostic(
                      "An edited learner test template at " + label + ".",
                      "The editable template is unchanged.",
                      "Complete the analogous test, then run check to verify compilation and behavior."));
              if (category == null) {
                category = FailureCategory.INCOMPLETE_WORK;
              }
            } else {
              byte[] original = assets.load(Objects.requireNonNull(declarations.get(path)));
              if (!sha256(original).equals(baseline.sha256())) {
                throw new IllegalArgumentException("Installed baseline mismatch");
              }
              boolean whitespaceOnly =
                  withoutWhitespace(original).equals(withoutWhitespace(content.orElseThrow()));
              observations.add(
                  new Diagnostic(
                      "An edited, compiling test at " + label + ".",
                      whitespaceOnly
                          ? "Only whitespace differs from the disclosed template; bytes changed without evidence of test work."
                          : "Template bytes changed; a textual edit alone does not prove understanding.",
                      "Review the test's assertions; compilation and independent public behavior still need validation."));
            }
          }
          case LEARNER_SCAFFOLD -> {}
        }
      } catch (IOException unsafe) {
        observations.add(
            new Diagnostic(
                "A bounded regular artifact at " + label + " inside the workspace.",
                "The artifact could not be read safely.",
                "Replace symlinks or conflicting paths with regular files, stop concurrent moves, and retry."));
        category = FailureCategory.WORKSPACE_CONFLICT;
      } catch (IllegalArgumentException invalidCourse) {
        return new CheckOutcome.Failed(
            FailureCategory.INTERNAL_ERROR,
            List.of(
                new Diagnostic(
                    "Matching installed course baseline bytes.",
                    "The installed baseline could not be validated.",
                    "Check the installed course content before retrying.")));
      }
    }
    return category == null
        ? new CheckOutcome.Passed(observations)
        : new CheckOutcome.Failed(category, observations);
  }

  private static String withoutWhitespace(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    StringBuilder result = new StringBuilder();
    text.codePoints()
        .filter(character -> !Character.isWhitespace(character))
        .forEach(result::appendCodePoint);
    return result.toString();
  }

  private static String display(String path) {
    StringBuilder safe = new StringBuilder();
    path.codePoints()
        .filter(
            character ->
                Character.getType(character) != Character.CONTROL
                    && Character.getType(character) != Character.FORMAT)
        .limit(200)
        .forEach(safe::appendCodePoint);
    return safe.toString();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Java must provide SHA-256.", unavailable);
    }
  }
}
