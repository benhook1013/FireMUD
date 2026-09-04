package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V6__complete_handler_and_ingress_identityTest {
  @Test
  void retainsRowsAndInstallsCompleteNullSafeIdentities() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V6__complete_handler_and_ingress_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ADD COLUMN binding_id VARCHAR(128)")
        .contains("DROP CONSTRAINT uq_script_work_item_trigger_identity")
        .contains("DROP CONSTRAINT uq_script_event_audit_handler_identity")
        .contains("CREATE UNIQUE INDEX uq_script_work_item_complete_handler_identity")
        .contains("CREATE UNIQUE INDEX uq_script_event_audit_complete_handler_identity")
        .contains("CREATE UNIQUE INDEX uq_script_event_ingress_audit_null_safe_identity")
        .contains("DO $$")
        .contains("RAISE EXCEPTION")
        .contains("retained duplicate rows exist")
        .contains("plugin_id")
        .contains("plugin_version_id")
        .contains("binding_id")
        .containsPattern(
            "(?s)CREATE UNIQUE INDEX uq_script_work_item_complete_handler_identity.*?"
                + "ON script_work_items.*?NULLS NOT DISTINCT")
        .containsPattern(
            "(?s)CREATE UNIQUE INDEX uq_script_event_audit_complete_handler_identity.*?"
                + "ON script_event_audit.*?NULLS NOT DISTINCT")
        .containsPattern(
            "(?s)CREATE UNIQUE INDEX uq_script_event_ingress_audit_null_safe_identity.*?"
                + "ON script_event_ingress_audit.*?NULLS NOT DISTINCT")
        .doesNotContain("DELETE FROM script_work_items")
        .doesNotContain("DELETE FROM script_event_audit")
        .doesNotContain("DELETE FROM script_event_ingress_audit");
  }
}
