package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class FlywayPostgresqlLockConfigurationTest {
  @Test
  void identityValidationScansFinishBeforeStrongLockFinalization() throws IOException {
    String migration =
        Files.readString(
            Path.of("src/main/resources/db/migration/V4__validate_actor_identity_constraints.sql"));
    int lastValidation = migration.lastIndexOf("VALIDATE CONSTRAINT ");
    int setNotNull = migration.indexOf("ALTER COLUMN character_uuid SET NOT NULL");
    int dropProofCheck = migration.indexOf("DROP CONSTRAINT ck_characters_character_uuid_nonnull");

    assertThat(lastValidation).isGreaterThanOrEqualTo(0);
    assertThat(setNotNull).isGreaterThan(lastValidation);
    assertThat(dropProofCheck).isGreaterThan(setNotNull);
    assertThat(migration).contains("VALIDATE CONSTRAINT ck_characters_character_uuid_nonnull");
  }

  @Test
  void allServiceProfilesBindSessionLevelPostgresqlAdvisoryLocks() throws IOException {
    for (Path resourcePath :
        List.of(
            Path.of("src/main/resources/application.yml"),
            Path.of("src/main/resources/application-prod.yml"),
            Path.of("src/test/resources/application.yml"))) {
      var propertySource =
          new YamlPropertySourceLoader()
              .load(resourcePath.toString(), new FileSystemResource(resourcePath.toFile()))
              .getFirst();
      var environment = new StandardEnvironment();
      environment.getPropertySources().addFirst(propertySource);

      FlywayProperties properties =
          Binder.get(environment)
              .bind("spring.flyway", FlywayProperties.class)
              .orElseThrow(
                  () -> new AssertionError("Flyway settings did not bind from " + resourcePath));

      assertThat(properties.getPostgresql().getTransactionalLock())
          .as("PostgreSQL transactional advisory locks in %s", resourcePath)
          .isFalse();
    }
  }
}
