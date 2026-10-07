package net.firedevops.firemud.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PostgresImageCompatibilityTest {
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
                "registry.invalid/" + "postgres:16-alpine"));
  }
}
