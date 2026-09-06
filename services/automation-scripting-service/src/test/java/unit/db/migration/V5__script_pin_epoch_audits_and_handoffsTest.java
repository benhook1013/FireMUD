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
    String normalized = migration.replaceAll("\\s+", " ");

    assertThat(migration)
        .contains(
            "UPDATE script_event_ingress_audit",
            "SET claim_started_at = created_at;",
            "SET region_id = COALESCE(region_id, ''),",
            "region_epoch = COALESCE(region_epoch, 0),",
            "entity_id = COALESCE(entity_id, ''),",
            "playable_state_scope = COALESCE(playable_state_scope, '')",
            "WHERE game_instance_id IS NOT NULL",
            "HAVING COUNT(*) > 1",
            "RAISE EXCEPTION",
            "duplicate pre-instance script ingress identities require operator reconciliation",
            "duplicate retained runtime script ingress identities require operator reconciliation",
            "WHERE game_instance_id IS NULL",
            "CREATE UNIQUE INDEX uq_script_event_audit_handler_identity ON script_event_audit",
            "ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT ''",
            "ADD COLUMN target_scope_type VARCHAR(32) NOT NULL DEFAULT ''",
            "ADD COLUMN target_scope_id VARCHAR(128) NOT NULL DEFAULT ''",
            ") WHERE script_pin_epoch > 0;",
            "CREATE UNIQUE INDEX uq_script_event_audit_handler_identity_unpinned ON script_event_audit",
            ") WHERE script_pin_epoch IS NULL;",
            "ALTER TABLE script_handoff_events",
            "ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;",
            "ADD COLUMN script_pin_control_plane_request_id VARCHAR(256);",
            "ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT '';",
            "ADD COLUMN request_digest VARCHAR(64) NOT NULL DEFAULT '';",
            "request_digest = '' OR request_digest ~ '^[0-9a-f]{64}$'",
            "ck_script_event_ingress_audit_request_digest",
            "/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */",
            "ck_script_handoff_events_pin_tuple",
            "script_pin_control_plane_request_id")
        .doesNotContain(
            "ADD CONSTRAINT uq_script_event_audit_handler_identity UNIQUE",
            "DELETE FROM script_event_ingress_audit",
            "ROW_NUMBER() OVER");

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
    int normalizedRuntimeStart =
        normalized.indexOf("CREATE UNIQUE INDEX uq_script_event_ingress_audit_runtime_identity");
    int runtimeEnd = migration.indexOf(") WHERE", runtimeStart);
    assertThat(runtimeStart).isGreaterThanOrEqualTo(0);
    assertThat(runtimeEnd).isGreaterThan(runtimeStart);
    assertThat(migration.substring(runtimeStart, runtimeEnd))
        .contains("playable_state_scope", "script_pin_epoch")
        .doesNotContain("script_pin_control_plane_request_id");
    assertThat(migration)
        .contains(") WHERE game_instance_id IS NOT NULL AND script_pin_epoch IS NOT NULL;");

    assertThat(migration)
        .doesNotContain(
            "CREATE UNIQUE INDEX uq_script_event_ingress_audit_runtime_unpinned_identity");

    int playableScopeDrop =
        normalized.indexOf(
            "ALTER TABLE script_event_ingress_audit ALTER COLUMN playable_state_scope DROP NOT NULL;");
    int preInstanceNormalization =
        normalized.indexOf(
            "UPDATE script_event_ingress_audit SET game_instance_id = NULL, playable_state_scope = NULL WHERE game_instance_id IS NULL OR game_instance_id = '';");
    assertThat(playableScopeDrop).isGreaterThanOrEqualTo(0);
    assertThat(preInstanceNormalization).isGreaterThan(playableScopeDrop);
    assertThat(preInstanceNormalization).isLessThan(normalizedRuntimeStart);

    int reconciliationStart =
        normalized.indexOf(
            "UPDATE script_event_ingress_audit SET region_id = COALESCE(region_id, ''), region_epoch = COALESCE(region_epoch, 0), entity_id = COALESCE(entity_id, ''),");
    assertThat(reconciliationStart).isGreaterThanOrEqualTo(0);
    assertThat(normalizedRuntimeStart).isGreaterThan(reconciliationStart);
    int duplicateIdentityCheck = normalized.indexOf("DO $$");
    assertThat(duplicateIdentityCheck).isGreaterThanOrEqualTo(0);
    assertThat(preInstanceNormalization).isLessThan(duplicateIdentityCheck);
    assertThat(reconciliationStart).isLessThan(duplicateIdentityCheck);
    assertThat(normalized.substring(reconciliationStart, duplicateIdentityCheck))
        .contains("entity_id = COALESCE(entity_id, '')", "WHERE game_instance_id IS NOT NULL")
        .doesNotContain("NULLS NOT DISTINCT", "WHERE game_instance_id IS NULL");
    assertThat(normalized.substring(duplicateIdentityCheck, normalizedRuntimeStart))
        .contains(
            "WHERE game_instance_id IS NULL",
            "AND script_id IS NOT NULL",
            "WHERE game_instance_id IS NOT NULL",
            "AND entity_id IS NOT NULL",
            "script_pin_epoch IS NULL",
            "HAVING COUNT(*) > 1",
            "RAISE EXCEPTION")
        .doesNotContain("NULLS NOT DISTINCT");

    int ingressTupleStart =
        migration.indexOf("ADD CONSTRAINT ck_script_event_ingress_audit_pin_tuple");
    int ingressTupleEnd =
        migration.indexOf(
            "CREATE UNIQUE INDEX uq_script_event_ingress_audit_onload_identity", ingressTupleStart);
    assertThat(ingressTupleStart).isGreaterThanOrEqualTo(0);
    assertThat(ingressTupleEnd).isGreaterThan(ingressTupleStart);
    assertThat(migration.substring(ingressTupleStart, ingressTupleEnd))
        .contains(
            "script_pin_epoch IS NULL",
            "script_pin_epoch > 0",
            "game_instance_id IS NOT NULL",
            "script_pin_control_plane_request_id");
    int normalizedIngressTupleStart =
        normalized.indexOf("ADD CONSTRAINT ck_script_event_ingress_audit_pin_tuple");
    int normalizedIngressTupleEnd =
        normalized.indexOf(
            "CREATE UNIQUE INDEX uq_script_event_ingress_audit_onload_identity",
            normalizedIngressTupleStart);
    assertThat(normalizedIngressTupleStart).isGreaterThanOrEqualTo(0);
    assertThat(normalizedIngressTupleEnd).isGreaterThan(normalizedIngressTupleStart);
    assertThat(normalized.substring(normalizedIngressTupleStart, normalizedIngressTupleEnd))
        .doesNotContain("script_pin_epoch IS NULL AND game_instance_id IS NULL");

    int auditTupleStart = migration.indexOf("ADD CONSTRAINT ck_script_event_audit_pin_tuple");
    int auditTupleEnd = migration.indexOf("ALTER TABLE script_handoff_events", auditTupleStart);
    assertThat(auditTupleStart).isGreaterThanOrEqualTo(0);
    assertThat(auditTupleEnd).isGreaterThan(auditTupleStart);
    assertThat(migration.substring(auditTupleStart, auditTupleEnd))
        .contains(
            "script_pin_epoch > 0",
            "game_instance_id IS NOT NULL",
            "script_pin_control_plane_request_id");

    int normalizedAuditTupleStart =
        normalized.indexOf("ADD CONSTRAINT ck_script_event_audit_pin_tuple");
    int normalizedHandoffTupleStart =
        normalized.indexOf("ADD CONSTRAINT ck_script_handoff_events_pin_tuple");
    assertThat(normalizedAuditTupleStart).isGreaterThan(normalizedIngressTupleStart);
    assertThat(normalizedHandoffTupleStart).isGreaterThan(normalizedAuditTupleStart);

    String notValidMarker = "/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */";
    int ingressTupleNotValid = normalized.indexOf(notValidMarker, normalizedIngressTupleStart);
    int auditTupleNotValid = normalized.indexOf(notValidMarker, normalizedAuditTupleStart);
    int handoffTupleNotValid = normalized.indexOf(notValidMarker, normalizedHandoffTupleStart);
    assertThat(ingressTupleNotValid).isGreaterThan(normalizedIngressTupleStart);
    assertThat(auditTupleNotValid).isGreaterThan(normalizedAuditTupleStart);
    assertThat(handoffTupleNotValid).isGreaterThan(normalizedHandoffTupleStart);
    assertThat(ingressTupleNotValid).isLessThan(normalizedAuditTupleStart);
    assertThat(auditTupleNotValid).isLessThan(normalizedHandoffTupleStart);

    int auditPinnedStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_event_audit_handler_identity ON");
    int auditPinnedEnd = migration.indexOf(") WHERE", auditPinnedStart);
    assertThat(auditPinnedStart).isGreaterThanOrEqualTo(0);
    assertThat(auditPinnedEnd).isGreaterThan(auditPinnedStart);
    assertThat(migration.substring(auditPinnedStart, auditPinnedEnd))
        .contains("script_pin_epoch", "script_event_id", "dry_run")
        .doesNotContain("script_pin_control_plane_request_id");

    int normalizedOnLoadStart =
        normalized.indexOf("CREATE UNIQUE INDEX uq_script_event_ingress_audit_onload_identity");
    int normalizedOnLoadEnd = normalized.indexOf(") WHERE", normalizedOnLoadStart);
    assertThat(normalizedOnLoadStart).isGreaterThan(normalizedRuntimeStart);
    assertThat(normalizedOnLoadStart).isGreaterThanOrEqualTo(0);
    assertThat(normalizedOnLoadStart).isGreaterThan(duplicateIdentityCheck);
    assertThat(normalizedOnLoadEnd).isGreaterThan(normalizedOnLoadStart);
    assertThat(normalized.substring(normalizedOnLoadStart, normalizedOnLoadEnd))
        .contains("script_id")
        .doesNotContain("region_id", "region_epoch", "game_instance_id");
    assertThat(migration)
        .contains(") WHERE game_instance_id IS NULL AND script_pin_epoch IS NULL;");
  }
}
