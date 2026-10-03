package org.fruitandfaults.course.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.application.CourseCatalog;
import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.CompletionCriterion;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.CourseId;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonAsset;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.course.domain.ReflectionOption;
import org.fruitandfaults.course.domain.ReflectionQuestion;
import org.jspecify.annotations.Nullable;

/** Loads explicitly declared Properties metadata and UTF-8 text from the classpath. */
public final class ClasspathCourseCatalog implements CourseCatalog, CourseAssets {
  private static final int MAX_RESOURCE_BYTES = 1_048_576;
  private static final int MAX_LIST_ENTRIES = 100;
  private final String root;
  private final ClassLoader classLoader;

  /**
   * Uses this adapter's classloader to load a bundle.
   *
   * @param root normalized relative classpath bundle root, without a trailing slash
   */
  public ClasspathCourseCatalog(String root) {
    this(root, ClasspathCourseCatalog.class.getClassLoader());
  }

  /**
   * Uses an explicitly selected classloader to load a bundle.
   *
   * @param root normalized relative classpath bundle root, without a trailing slash
   * @param classLoader source of classpath resources
   */
  public ClasspathCourseCatalog(String root, ClassLoader classLoader) {
    this.root = relativePath(root);
    this.classLoader = Objects.requireNonNull(classLoader);
  }

  @Override
  public Course load() {
    try {
      BundleProperties course = properties(root + "/course.properties");
      CourseId id = new CourseId(course.required("courseId"));
      int version;
      try {
        version = Integer.parseInt(course.required("contentVersion"));
      } catch (NumberFormatException failure) {
        throw new IllegalArgumentException(
            "Expected integer contentVersion; observed " + course.required("contentVersion") + ".",
            failure);
      }
      if (version != 1) {
        throw new IllegalArgumentException("Expected contentVersion 1; observed " + version + ".");
      }
      String title = course.required("title");
      List<LessonId> order = course.list("lessonOrder").stream().map(LessonId::new).toList();
      Set<String> keys =
          new HashSet<>(Set.of("courseId", "contentVersion", "title", "lessonOrder"));
      for (LessonId lessonId : order) {
        keys.add("lesson." + lessonId.value() + ".directory");
      }
      course.onlyKeys(keys);
      List<Lesson> lessons = new ArrayList<>();
      for (LessonId lessonId : order) {
        String directory = course.required("lesson." + lessonId.value() + ".directory");
        lessons.add(lesson(root + "/" + relativePath(directory), lessonId));
      }
      return new Course(id, version, title, order, lessons);
    } catch (IllegalArgumentException failure) {
      throw invalid(root + "/course.properties", failure.getMessage(), failure);
    }
  }

  @Override
  public byte[] load(LessonAsset asset) {
    String resource = relativePath(asset.resourcePath());
    if (!resource.startsWith(root + "/")) {
      throw new IllegalArgumentException("Expected an asset within the installed course bundle.");
    }
    byte[] raw = bytes(resource);
    if (!sha256(raw).equals(asset.sha256())) {
      throw new IllegalArgumentException("Expected asset bytes matching the course declaration.");
    }
    return raw;
  }

  private Lesson lesson(String directory, LessonId expectedId) {
    try {
      return readLesson(directory, expectedId);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException(
          "Invalid lesson at " + directory + "/lesson.properties: " + failure.getMessage(),
          failure);
    }
  }

  private Lesson readLesson(String directory, LessonId expectedId) {
    BundleProperties metadata = properties(directory + "/lesson.properties");
    LessonId id = new LessonId(metadata.required("id"));
    if (!id.equals(expectedId)) {
      throw new IllegalArgumentException(
          "Expected lesson identity "
              + expectedId.value()
              + " at "
              + metadata.resource
              + "; observed "
              + id.value()
              + ".");
    }
    List<String> assetIds = metadata.list("assets");
    List<String> criterionIds = metadata.list("completionCriteria");
    Set<String> keys =
        new HashSet<>(
            Set.of(
                "id",
                "title",
                "goal",
                "prerequisites",
                "assets",
                "expectedArtifacts",
                "completionCriteria",
                "instructions",
                "hints",
                "question",
                "recommendedCommitMessage"));
    for (String assetId : assetIds) {
      for (String suffix : List.of("path", "resource", "policy", "sha256")) {
        keys.add("asset." + assetId + "." + suffix);
      }
    }
    for (String criterionId : criterionIds) {
      keys.add("criterion." + criterionId + ".description");
    }
    metadata.onlyKeys(keys);
    List<LessonAsset> assets = new ArrayList<>();
    for (String assetId : assetIds) {
      String prefix = "asset." + assetId + ".";
      AssetId assetIdentity = new AssetId(assetId);
      String path = relativePath(metadata.required(prefix + "path"));
      String resource = directory + "/" + relativePath(metadata.required(prefix + "resource"));
      AssetPolicy policy;
      try {
        policy = AssetPolicy.valueOf(metadata.required(prefix + "policy"));
      } catch (IllegalArgumentException failure) {
        throw new IllegalArgumentException(
            "Expected asset policy IMMUTABLE_CHECK, EDITABLE_TEMPLATE, or LEARNER_SCAFFOLD; observed '"
                + metadata.required(prefix + "policy")
                + "'.",
            failure);
      }
      String hash = sha256(bytes(resource));
      if (metadata.values.containsKey(prefix + "sha256")
          && !hash.equals(metadata.required(prefix + "sha256"))) {
        throw new IllegalArgumentException(
            "Expected declared SHA-256 to match raw asset bytes at " + resource + ".");
      }
      assets.add(new LessonAsset(assetIdentity, path, resource, hash, policy));
    }
    List<CompletionCriterion> criteria =
        criterionIds.stream()
            .map(
                criterionId ->
                    new CompletionCriterion(
                        criterionId,
                        metadata.required("criterion." + criterionId + ".description")))
            .toList();
    List<String> hints =
        metadata.list("hints").stream()
            .map(path -> text(directory + "/" + relativePath(path)))
            .toList();
    return new Lesson(
        id,
        metadata.required("title"),
        metadata.required("goal"),
        metadata.list("prerequisites").stream().map(LessonId::new).toList(),
        assets,
        metadata.list("expectedArtifacts"),
        criteria,
        text(directory + "/" + relativePath(metadata.required("instructions"))),
        hints,
        question(directory + "/" + relativePath(metadata.required("question"))),
        metadata.required("recommendedCommitMessage"));
  }

  private ReflectionQuestion question(String resource) {
    try {
      return readQuestion(resource);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException(
          "Invalid question at " + resource + ": " + failure.getMessage(), failure);
    }
  }

  private ReflectionQuestion readQuestion(String resource) {
    BundleProperties question = properties(resource);
    List<String> optionIds = question.list("options");
    Set<String> keys = new HashSet<>(Set.of("id", "prompt", "options", "correctOptionId"));
    for (String optionId : optionIds) {
      keys.add("option." + optionId + ".text");
      keys.add("option." + optionId + ".feedback");
    }
    question.onlyKeys(keys);
    List<ReflectionOption> options =
        optionIds.stream()
            .map(
                id ->
                    new ReflectionOption(
                        id,
                        question.required("option." + id + ".text"),
                        question.required("option." + id + ".feedback")))
            .toList();
    return new ReflectionQuestion(
        question.required("id"),
        question.required("prompt"),
        options,
        question.required("correctOptionId"));
  }

  private BundleProperties properties(String resource) {
    Properties values = new Properties();
    try {
      values.load(new StringReader(text(resource)));
      return new BundleProperties(resource, values);
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid(resource, failure.getMessage(), failure);
    }
  }

  private String text(String resource) {
    try {
      String result =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes(resource))).toString();
      if (result.isBlank()) {
        throw new IllegalArgumentException("Expected non-blank UTF-8 text at " + resource + ".");
      }
      return result;
    } catch (IOException failure) {
      throw invalid(resource, "Expected valid UTF-8 text", failure);
    }
  }

  private byte[] bytes(String resource) {
    try (InputStream input = classLoader.getResourceAsStream(resource)) {
      if (input == null) {
        throw invalid(resource, "Expected resource; observed missing resource", null);
      }
      byte[] result = input.readNBytes(MAX_RESOURCE_BYTES + 1);
      if (result.length > MAX_RESOURCE_BYTES) {
        throw invalid(resource, "Expected resource at most " + MAX_RESOURCE_BYTES + " bytes", null);
      }
      return result;
    } catch (IOException failure) {
      throw invalid(resource, "Expected readable resource; observed I/O failure", failure);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Java runtime lacks required SHA-256 support", failure);
    }
  }

  private static String relativePath(String value) {
    if (value.isBlank()
        || value.startsWith("/")
        || value.contains("\\")
        || value.contains(":")
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Expected normalized relative resource path; observed '" + value + "'.");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(
            "Expected normalized relative resource path; observed '" + value + "'.");
      }
    }
    return value;
  }

  private static IllegalArgumentException invalid(
      String resource, @Nullable String detail, @Nullable Throwable cause) {
    return new IllegalArgumentException(
        "Expected valid course bundle at "
            + resource
            + "; observed "
            + detail
            + ". Restore the installed course bundle and retry.",
        cause);
  }

  private record BundleProperties(String resource, Properties values) {
    String required(String key) {
      String value = values.getProperty(key);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "Expected non-blank property '" + key + "' at " + resource + ".");
      }
      return value.strip();
    }

    List<String> list(String key) {
      String value = values.getProperty(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "Expected explicit list property '" + key + "' at " + resource + ".");
      }
      if (value.isBlank()) {
        return List.of();
      }
      List<String> entries = List.of(value.split(",", -1)).stream().map(String::strip).toList();
      if (entries.size() > MAX_LIST_ENTRIES || entries.stream().anyMatch(String::isEmpty)) {
        throw new IllegalArgumentException(
            "Expected at most "
                + MAX_LIST_ENTRIES
                + " non-empty entries for '"
                + key
                + "' at "
                + resource
                + ".");
      }
      if (new HashSet<>(entries).size() != entries.size()) {
        throw new IllegalArgumentException(
            "Duplicate entries for '" + key + "' at " + resource + ".");
      }
      return entries;
    }

    void onlyKeys(Set<String> keys) {
      for (String key : values.stringPropertyNames()) {
        if (!keys.contains(key)) {
          throw new IllegalArgumentException(
              "Unexpected property '" + key + "' at " + resource + ".");
        }
      }
    }
  }
}
