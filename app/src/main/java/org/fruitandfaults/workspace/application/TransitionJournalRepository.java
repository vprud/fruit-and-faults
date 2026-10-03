package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.workspace.domain.TransitionJournal;

/** Workspace-scoped persistence of a write-once recovery plan. */
public interface TransitionJournalRepository {
  /**
   * Loads validated pending state, preserving invalid documents.
   *
   * @param root selected existing learner workspace
   * @return journal, empty only when absent
   * @throws IOException if unsafe, invalid, unsupported, or unreadable
   */
  Optional<TransitionJournal> load(Path root) throws IOException;

  /**
   * Exclusively creates a complete plan before the first learner asset write.
   *
   * @param root selected existing learner workspace
   * @param journal immutable valid plan
   * @throws IOException if already present, unsafe, unsupported, or writing fails
   */
  void create(Path root, TransitionJournal journal) throws IOException;

  /**
   * Removes only the exact validated journal after all committed facts have been verified.
   *
   * @param root selected existing learner workspace
   * @param journal exact expected pending plan
   * @throws IOException if the entry changed, access is unsafe, or removal fails
   */
  void remove(Path root, TransitionJournal journal) throws IOException;
}
