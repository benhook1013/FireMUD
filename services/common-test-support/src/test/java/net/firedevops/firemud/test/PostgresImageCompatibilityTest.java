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
  private static final String OFFICIAL_ECR_PREFIX = "public.ecr.aws/docker/library/";
  private static final String DIGEST = "sha256:" + "a".repeat(64);

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
    assertTrue(postgres.asCanonicalNameString().startsWith(OFFICIAL_ECR_PREFIX + "postgres:"));
    assertTrue(redis.asCanonicalNameString().startsWith(OFFICIAL_ECR_PREFIX + "redis:"));
    assertDoesNotThrow(() -> postgres.assertCompatibleWith(DockerImageName.parse("postgres")));
    assertDoesNotThrow(() -> new PostgreSQLContainer<>(postgres));
    assertDoesNotThrow(() -> new GenericContainer<>(redis));
  }

  @Test
  void acceptsDigestPinnedServiceSpecificEcrSelections() {
    Properties images = new Properties();
    for (String repository : new String[] {"postgres", "redis"}) {
      String reference = OFFICIAL_ECR_PREFIX + repository + ":18-alpine@" + DIGEST;
      images.setProperty(repository + ".image", reference);
      assertEquals(reference, TestContainerImages.selectedReference(images, repository));
    }
  }

  @Test
  void rejectsMissingMalformedUnpinnedOrWrongRepositorySelections() {
    Properties images = new Properties();
    for (String repository : new String[] {"postgres", "redis"}) {
      assertThrows(
          IllegalStateException.class,
          () -> TestContainerImages.selectedReference(images, repository));
      String otherRepository = repository.equals("postgres") ? "redis" : "postgres";
      for (String invalid :
          new String[] {
            "",
            repository + ":18@" + DIGEST,
            OFFICIAL_ECR_PREFIX + repository + ":18",
            OFFICIAL_ECR_PREFIX + repository + ":18@sha256:invalid",
            OFFICIAL_ECR_PREFIX + repository + "@" + DIGEST,
            OFFICIAL_ECR_PREFIX + otherRepository + ":18@" + DIGEST,
            "registry.invalid/docker/library/" + repository + ":18@" + DIGEST,
            "publicXecrXaws/docker/library/" + repository + ":18@" + DIGEST,
            "public.ecr.aws/other/" + repository + ":18@" + DIGEST
          }) {
        images.setProperty(repository + ".image", invalid);
        assertThrows(
            IllegalStateException.class,
            () -> TestContainerImages.selectedReference(images, repository));
      }
    }
  }

  @Test
  void acceptsCanonicalPostgresTaggedDigestsAsCompatibleSubstitutes() {
    for (String tag : new String[] {"18", "18-alpine"}) {
      String imageReference = OFFICIAL_ECR_PREFIX + "postgres:" + tag + "@" + DIGEST;
      DockerImageName image = PostgresBackedServiceTestSupport.postgresImage(imageReference);

      assertEquals(imageReference, image.asCanonicalNameString());
      assertDoesNotThrow(() -> image.assertCompatibleWith(DockerImageName.parse("postgres")));
      assertDoesNotThrow(() -> new PostgreSQLContainer<>(image));
    }
  }

  @Test
  void rejectsUnpinnedMalformedOrNoncanonicalPostgresImages() {
    for (String invalid :
        new String[] {
          "postgres:18@" + DIGEST,
          OFFICIAL_ECR_PREFIX + "postgres:18",
          OFFICIAL_ECR_PREFIX + "postgres:18@sha256:invalid",
          OFFICIAL_ECR_PREFIX + "redis:18@" + DIGEST,
          "registry.invalid/docker/library/postgres:18@" + DIGEST,
          "publicXecrXaws/docker/library/postgres:18@" + DIGEST,
          "public.ecr.aws/other/postgres:18@" + DIGEST
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> PostgresBackedServiceTestSupport.postgresImage(invalid));
    }
  }
}
