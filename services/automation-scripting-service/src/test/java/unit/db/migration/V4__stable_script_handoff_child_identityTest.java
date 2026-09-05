package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V4__stable_script_handoff_child_identityTest {
  @Test
  void repairsDuplicatesBeforeInstallingStableChildIdentity() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V4__stable_script_handoff_child_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("CREATE TABLE script_handoff_event_identity_repairs")
        .contains("MIN(id) AS survivor_id")
        .contains("INSERT INTO script_handoff_event_identity_repairs")
        .contains("DELETE FROM script_handoff_events duplicate")
        .contains("CREATE UNIQUE INDEX uq_script_handoff_events_logical_child")
        .contains("duplicate_row_id")
        .contains("survivor_row_id")
        .contains("repaired_at TIMESTAMPTZ");

    assertThat(migration.indexOf("CREATE TABLE script_handoff_event_identity_repairs"))
        .isLessThan(migration.indexOf("INSERT INTO script_handoff_event_identity_repairs"));
    assertThat(migration.indexOf("INSERT INTO script_handoff_event_identity_repairs"))
        .isLessThan(migration.indexOf("DELETE FROM script_handoff_events duplicate"));
    assertThat(migration.indexOf("DELETE FROM script_handoff_events duplicate"))
        .isLessThan(
            migration.indexOf("CREATE UNIQUE INDEX uq_script_handoff_events_logical_child"));
  }
}
