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
            "UPDATE script_event_ingress_audit",
            "SET region_id = COALESCE(region_id, ''),",
            "region_epoch = COALESCE(region_epoch, 0),",
            "playable_state_scope = COALESCE(playable_state_scope, '')",
            "WHERE game_instance_id IS NOT NULL",
            "ROW_NUMBER() OVER",
            "DELETE FROM script_event_ingress_audit",
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

    int runtimeStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_event_ingress_audit_runtime_identity");
    int runtimeEnd = migration.indexOf(") WHERE", runtimeStart);
    assertThat(runtimeStart).isGreaterThanOrEqualTo(0);
    assertThat(runtimeEnd).isGreaterThan(runtimeStart);
    assertThat(migration.substring(runtimeStart, runtimeEnd)).contains("playable_state_scope");
    assertThat(migration)
        .contains(") WHERE game_instance_id IS NOT NULL AND script_pin_epoch IS NOT NULL;");

    int reconciliationStart = migration.indexOf("UPDATE script_event_ingress_audit");
    assertThat(reconciliationStart).isGreaterThanOrEqualTo(0);
    assertThat(runtimeStart).isGreaterThan(reconciliationStart);
    assertThat(migration.substring(reconciliationStart, runtimeStart))
        .contains(
            "WHERE game_instance_id IS NOT NULL",
            "script_pin_epoch IS NOT NULL",
            "script_pin_epoch IS NULL")
        .doesNotContain("NULLS NOT DISTINCT", "WHERE game_instance_id IS NULL");

    int onLoadStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_event_ingress_audit_onload_identity");
    int onLoadEnd = migration.indexOf(") WHERE", onLoadStart);
    assertThat(onLoadStart).isGreaterThan(runtimeStart);
    assertThat(onLoadEnd).isGreaterThan(onLoadStart);
    assertThat(migration.substring(onLoadStart, onLoadEnd))
        .contains("script_id")
        .doesNotContain("region_id", "region_epoch", "game_instance_id");
    assertThat(migration)
        .contains(") WHERE game_instance_id IS NULL AND script_pin_epoch IS NULL;");
  }
}
