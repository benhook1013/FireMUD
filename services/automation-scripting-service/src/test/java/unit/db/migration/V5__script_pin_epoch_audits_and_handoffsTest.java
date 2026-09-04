package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V5__script_pin_epoch_audits_and_handoffsTest {
  @Test
  void usesDistinctPinnedAndUnpinnedHandlerIdentityIndexes() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V5__script_pin_epoch_audits_and_handoffs.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "CREATE UNIQUE INDEX uq_script_event_audit_handler_identity ON script_event_audit",
            "script_pin_control_plane_request_id,",
            ") WHERE script_pin_epoch > 0;",
            "CREATE UNIQUE INDEX uq_script_event_audit_handler_identity_unpinned ON script_event_audit",
            ") WHERE script_pin_epoch IS NULL;")
        .doesNotContain("ADD CONSTRAINT uq_script_event_audit_handler_identity UNIQUE");

    int unpinnedStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_event_audit_handler_identity_unpinned");
    int unpinnedEnd = migration.indexOf(") WHERE", unpinnedStart);
    assertThat(unpinnedStart).isGreaterThanOrEqualTo(0);
    assertThat(unpinnedEnd).isGreaterThan(unpinnedStart);
    assertThat(migration.substring(unpinnedStart, unpinnedEnd))
        .contains("script_event_id", "dry_run")
        .doesNotContain("script_pin_epoch", "script_pin_control_plane_request_id");
  }
}
