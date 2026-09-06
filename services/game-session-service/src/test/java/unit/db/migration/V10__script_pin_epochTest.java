package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V10__script_pin_epochTest {
  @Test
  void normalizesLegacyPartialPinRowsBeforeAddingTupleConstraint() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V10__script_pin_epoch.sql")) {
      assertThat(stream).isNotNull();
      migration =
          new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    assertThat(migration)
        .contains("ADD COLUMN script_pin_epoch bigint")
        .contains("UPDATE game_instances")
        .contains("SET script_patch_version = NULL, script_pin_epoch = NULL")
        .contains("SET script_pin_epoch = 1")
        .contains("script_pin_epoch IS NULL")
        .contains("script_patch_version")
        .contains("script_patch_pinned_control_plane_request_id")
        .contains("ADD CONSTRAINT game_instances_script_pin_tuple_coherent")
        .contains("regexp_replace(script_patch_version, '[[:space:]]', '', 'g')")
        .contains(
            "regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g')");
    assertThat(migration)
        .contains(
            "WHERE ( NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL ) OR ( NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL );")
        .contains(
            "WHERE NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL AND script_pin_epoch IS NULL;")
        .contains("/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */");
    assertThat(migration.indexOf("UPDATE game_instances"))
        .isLessThan(migration.indexOf("ADD CONSTRAINT game_instances_script_pin_tuple_coherent"));
    assertThat(migration.indexOf("SET script_patch_version = NULL"))
        .isLessThan(migration.indexOf("SET script_pin_epoch = 1"));
    assertThat(migration.indexOf("SET script_pin_epoch = 1"))
        .isLessThan(migration.indexOf("ADD CONSTRAINT game_instances_script_pin_tuple_coherent"));
  }
}
