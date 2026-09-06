package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14__clear_unpinned_script_pin_metadataTest {
  @Test
  void clearsLegacyMetadataOnlyForTheCompleteSemanticUnpinnedTuple() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V14__clear_unpinned_script_pin_metadata.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("SET script_patch_pinned_at = NULL")
        .contains("script_patch_pinned_by = NULL")
        .contains("script_patch_pinned_reason = NULL")
        .contains("script_pin_epoch IS NULL")
        .contains("script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g')")
        .contains("ADD CONSTRAINT game_instances_unpinned_script_pin_metadata_coherent CHECK")
        .contains("script_pin_epoch IS NOT NULL")
        .contains("script_pin_epoch > 0")
        .contains("script_patch_pinned_at IS NULL")
        .contains("script_patch_pinned_by IS NULL")
        .contains("script_patch_pinned_reason IS NULL")
        .contains("/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */");

    int update = migration.indexOf("UPDATE game_instances");
    int constraint = migration.indexOf("ADD CONSTRAINT");
    assertThat(update).isGreaterThanOrEqualTo(0);
    assertThat(constraint).isGreaterThan(update);

    String constraintDefinition = migration.substring(constraint);
    String[] alternatives = constraintDefinition.split("\\)\\s+OR\\s+\\(", -1);
    assertThat(alternatives).hasSize(2);
    assertThat(alternatives[0])
        .contains("script_pin_epoch IS NOT NULL")
        .contains("script_pin_epoch > 0")
        .contains("script_patch_pinned_control_plane_request_id");
    assertThat(alternatives[1])
        .contains("script_pin_epoch IS NULL")
        .contains("script_patch_pinned_at IS NULL")
        .contains("script_patch_pinned_by IS NULL")
        .contains("script_patch_pinned_reason IS NULL");
  }

  @Test
  void doesNotClearMetadataFromPinnedRows() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V14__clear_unpinned_script_pin_metadata.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String where =
        migration.substring(
            migration.indexOf("WHERE"), migration.indexOf(";", migration.indexOf("WHERE")));
    assertThat(where)
        .contains("NULLIF(regexp_replace(script_patch_version")
        .contains("AND script_pin_epoch IS NULL")
        .contains("script_pin_epoch IS NULL")
        .contains("NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id")
        .doesNotContainPattern("\\bOR\\b");
  }
}
