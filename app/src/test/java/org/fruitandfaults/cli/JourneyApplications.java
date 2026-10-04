package org.fruitandfaults.cli;

import java.util.function.Function;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.application.CourseCatalog;
import org.fruitandfaults.git.infra.ProcessGitRepository;
import org.fruitandfaults.validation.application.CheckRequest;
import org.fruitandfaults.validation.domain.CheckOutcome;

/** Test-only access to the installed composition for deterministic external failure seams. */
public final class JourneyApplications {
  private JourneyApplications() {}

  /**
   * Keeps all installed workspace/Git adapters real while selecting trusted test content/checks.
   *
   * @param catalog trusted current and historical test course
   * @param assets declared bytes
   * @param checks real checks or explicitly labelled deterministic failure seam
   * @return real composed command ports
   */
  public static ApplicationFactory.Application compose(
      CourseCatalog catalog, CourseAssets assets, Function<CheckRequest, CheckOutcome> checks) {
    return ApplicationFactory.compose(catalog, assets, checks, new ProcessGitRepository());
  }
}
