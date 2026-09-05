package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V13__automation_admission_request_historyTest {
  @Test
  void storesAdmissionHistoryActorPrincipalAtCanonicalWidth() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V13__automation_admission_request_history.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("CREATE TABLE automation_admission_request_history")
        .contains("actor_principal VARCHAR(256) NOT NULL")
        .doesNotContain("actor_principal VARCHAR(128) NOT NULL");
  }
}
