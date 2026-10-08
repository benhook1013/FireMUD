package net.firedevops.firemud.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PostgresImageCompatibilityTest {
  @Test
  void usesSelectedClasspathImageReferencesForContainers() throws Exception {
    Properties images = new Properties();
    try (var stream =
        TestContainerImages.class.getResourceAsStream("container-images.properties")) {
      images.load(java.util.Objects.requireNonNull(stream));
    }
    DockerImageName postgres = TestContainerImages.postgres();
    DockerImageName redis = TestContainerImages.redis();

    assertEquals(images.getProperty("postgres.image"), postgres.asCanonicalNameString());
    assertEquals(images.getProperty("redis.image"), redis.asCanonicalNameString());
    assertTrue(postgres.asCanonicalNameString().matches("postgres:[^@]+@sha256:[0-9a-f]{64}"));
    assertTrue(
        redis.getRepository().equals("redis")
            || (redis.getRepository().matches("redis:[^@]+")
                && redis.asCanonicalNameString().startsWith(redis.getRepository() + "@sha256:")));
    assertDoesNotThrow(() -> new PostgreSQLContainer<>(postgres));
    assertDoesNotThrow(() -> new GenericContainer<>(redis));
  }

  @Test
  void rejectsMissingMalformedOrUnpinnedPostgresSelections() {
    Properties images = new Properties();
    assertThrows(
        IllegalStateException.class,
        () -> TestContainerImages.selectedReference(images, "postgres", true));
    for (String invalid :
        new String[] {
          "", "postgres:" + "18", "postgres:18@sha256:invalid", "redis:7@sha256:" + "0".repeat(64)
        }) {
      images.setProperty("postgres.image", invalid);
      assertThrows(
          IllegalStateException.class,
          () -> TestContainerImages.selectedReference(images, "postgres", true));
    }
    images.setProperty("redis.image", "postgres:" + "18");
    assertThrows(
        IllegalStateException.class,
        () -> TestContainerImages.selectedReference(images, "redis", false));
  }

  @Test
  void acceptsCanonicalPostgresTagAndDigestReferencesAsCompatibleSubstitutes() {
    for (String imageReference :
        new String[] {
          "postgres:" + "16-alpine",
          "postgres:16-alpine@"
              + "sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea",
          "postgres:18-alpine@"
              + "sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"
        }) {
      DockerImageName image = PostgresBackedServiceTestSupport.postgresImage(imageReference);

      assertEquals(imageReference, image.asCanonicalNameString());
      assertDoesNotThrow(() -> new PostgreSQLContainer<>(image));
    }
  }

  @Test
  void rejectsImagesOutsideTheCanonicalPostgresRepository() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PostgresBackedServiceTestSupport.postgresImage("redis:" + "7-alpine"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PostgresBackedServiceTestSupport.postgresImage(
                "registry.invalid/" + "postgres:" + "16-alpine"));
  }
}
