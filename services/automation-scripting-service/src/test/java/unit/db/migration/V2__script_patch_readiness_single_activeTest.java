package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class V2__script_patch_readiness_single_activeTest {
  private static final String[] HANDLER_IDENTITY_COLUMNS = {
    "tenant_id",
    "game_instance_id",
    "region_id",
    "region_epoch",
    "entity_id",
    "playable_state_scope",
    "world_slug",
    "realm_slug",
    "pointer_version",
    "script_id",
    "plugin_id",
    "plugin_version_id",
    "binding_id",
    "event_type",
    "event_schema_version",
    "script_patch_version",
    "script_pin_epoch",
    "script_event_id",
    "dry_run",
    "source_service"
  };
  private static final String[] UNPINNED_HANDLER_IDENTITY_COLUMNS =
      Arrays.stream(HANDLER_IDENTITY_COLUMNS)
          .filter(column -> !column.equals("script_pin_epoch"))
          .toArray(String[]::new);

  @Test
  void replacesBothPinnedAndUnpinnedHandlerIndexesWithAuthenticatedSourceService()
      throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V2__script_patch_readiness_single_active.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains(
            "DO $v2_preflight$",
            "Automation V2 requires the four exact V1 producer-agnostic unique indexes",
            "Automation V2 found retained duplicate active readiness rows",
            "Automation V2 requires all active work-item and onLoad claims to drain before migration",
            "DROP INDEX uq_script_work_item_trigger_identity;",
            "DROP INDEX uq_script_work_item_trigger_identity_unpinned;",
            "DROP INDEX uq_script_event_audit_handler_identity;",
            "DROP INDEX uq_script_event_audit_handler_identity_unpinned;");

    assertThat(indexColumns(normalized, "uq_script_work_item_trigger_identity"))
        .containsExactly(HANDLER_IDENTITY_COLUMNS);
    assertThat(indexColumns(normalized, "uq_script_event_audit_handler_identity"))
        .containsExactly(HANDLER_IDENTITY_COLUMNS);
    assertThat(indexColumns(normalized, "uq_script_work_item_trigger_identity_unpinned"))
        .containsExactly(UNPINNED_HANDLER_IDENTITY_COLUMNS);
    assertThat(indexColumns(normalized, "uq_script_event_audit_handler_identity_unpinned"))
        .containsExactly(UNPINNED_HANDLER_IDENTITY_COLUMNS);
  }

  private static String[] indexColumns(String migration, String indexName) {
    Matcher matcher =
        Pattern.compile(
                "CREATE UNIQUE INDEX "
                    + Pattern.quote(indexName)
                    + " ON [^;]+?\\((.*?)\\)(?: NULLS| WHERE|;)")
            .matcher(migration);
    assertThat(matcher.find()).as("missing index %s", indexName).isTrue();
    return Arrays.stream(matcher.group(1).split(",")).map(String::trim).toArray(String[]::new);
  }
}
