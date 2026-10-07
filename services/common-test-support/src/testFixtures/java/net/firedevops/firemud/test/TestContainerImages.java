package net.firedevops.firemud.test;

import java.io.IOException;
import java.util.Properties;
import org.testcontainers.utility.DockerImageName;

/** Shared image selections from the fixture resource maintained by Renovate. */
public final class TestContainerImages {
  private static final Properties IMAGES = loadImages();

  private TestContainerImages() {}

  public static DockerImageName postgres() {
    return PostgresBackedServiceTestSupport.postgresImage(
        selectedReference(IMAGES, "postgres", true));
  }

  public static DockerImageName redis() {
    return DockerImageName.parse(selectedReference(IMAGES, "redis", false));
  }

  static String selectedReference(Properties images, String repository, boolean requireDigest) {
    String reference = images.getProperty(repository + ".image");
    String pattern =
        repository
            + ":[A-Za-z0-9_][A-Za-z0-9_.-]*"
            + "(?:@sha256:[0-9a-f]{64})"
            + (requireDigest ? "" : "?");
    if (reference == null || !reference.matches(pattern)) {
      throw new IllegalStateException(
          "Missing or malformed test image reference for " + repository);
    }
    return reference;
  }

  private static Properties loadImages() {
    Properties images = new Properties();
    try (var stream =
        TestContainerImages.class.getResourceAsStream("container-images.properties")) {
      if (stream == null) {
        throw new IllegalStateException("Missing shared Testcontainers image resource");
      }
      images.load(stream);
      return images;
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to load shared Testcontainers image resource", exception);
    }
  }
}
