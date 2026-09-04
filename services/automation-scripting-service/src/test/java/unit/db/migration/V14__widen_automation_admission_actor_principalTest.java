package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14__widen_automation_admission_actor_principalTest {
  @Test
  void widensMutableAdmissionActorPrincipalProjection() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V14__widen_automation_admission_actor_principal.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ALTER TABLE automation_admission_states")
        .contains("ALTER COLUMN actor_principal TYPE VARCHAR(256);")
        .doesNotContain("VARCHAR(128)");
  }
}
