package org.fruitandfaults.course.domain;

final class ContentValidation {
  private ContentValidation() {}

  static String text(String value, String name) {
    if (value.isBlank()) {
      throw new IllegalArgumentException("Expected non-blank " + name + "; observed blank text.");
    }
    return value;
  }

  static String identifier(String value, String name) {
    if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new IllegalArgumentException("Expected stable " + name + "; observed '" + value + "'.");
    }
    return value;
  }

  static String path(String value, String name) {
    text(value, name);
    if (value.startsWith("/")
        || value.contains("\\")
        || value.contains(":")
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Expected normalized relative " + name + "; observed '" + value + "'.");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(
            "Expected normalized relative " + name + "; observed '" + value + "'.");
      }
    }
    return value;
  }
}
