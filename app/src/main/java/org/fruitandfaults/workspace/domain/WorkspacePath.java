package org.fruitandfaults.workspace.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * A normalized slash-separated relative logical path, independent of the host filesystem.
 *
 * @param value relative logical destination
 */
public record WorkspacePath(String value) {
  /** Rejects empty segments, traversal, controls, and Unix or Windows absolute forms. */
  public WorkspacePath {
    if (value.isBlank()
        || value.startsWith("/")
        || value.contains("\\")
        || value.contains(":")
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Expected a normalized relative workspace path.");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("Expected a normalized relative workspace path.");
      }
    }
  }

  /**
   * Parses a logical path without silently normalizing unsafe input.
   *
   * @param value normalized relative path
   * @return validated workspace path
   */
  public static WorkspacePath parse(String value) {
    return new WorkspacePath(value);
  }

  /**
   * Identifies conservative case and Unicode-normalization aliases on every host.
   *
   * @return canonical key used consistently for portable destination comparison
   */
  public String aliasKey() {
    return Normalizer.normalize(value, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
  }

  /**
   * Identifies the reserved tool metadata tree, including portable spelling aliases.
   *
   * @return whether a learner asset would overlap tool-owned state
   */
  public boolean isToolMetadata() {
    String key = aliasKey();
    return key.equals(".fruit-and-faults") || key.startsWith(".fruit-and-faults/");
  }
}
