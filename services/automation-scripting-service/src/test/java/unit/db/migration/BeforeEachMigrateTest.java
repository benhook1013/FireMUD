package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BeforeEachMigrateTest {
  @Test
  void preflightsOnlyAfterV4ShapeAndChecksBothNormalizedIdentityBranches() throws IOException {
    String callback;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/beforeEachMigrate.sql")) {
      assertThat(stream).isNotNull();
      callback = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(callback)
        .contains("to_regclass('script_work_items') IS NULL")
        .contains("attname = 'script_pin_epoch'")
        .contains("NULLIF(BTRIM(plugin_id), '') IS NULL")
        .contains("NULLIF(BTRIM(plugin_version_id), '') IS NULL")
        .contains("WHEN plugin_id IS NULL OR plugin_version_id IS NULL THEN ''")
        .contains("ELSE plugin_id")
        .contains("ELSE plugin_version_id")
        .contains("WHERE script_pin_epoch > 0")
        .contains("WHERE script_pin_epoch = 0")
        .contains("HAVING COUNT(*) > 1")
        .contains("ERRCODE = '23514'")
        .contains("ERRCODE = '23505'")
        .doesNotContain("DELETE FROM")
        .doesNotContain("MERGE INTO");
  }

  @Test
  void callbackIsOrderedToRunBeforeVersionEightNormalization() throws IOException {
    String callback;
    String v8;
    try (var callbackStream =
            getClass().getClassLoader().getResourceAsStream("db/migration/beforeEachMigrate.sql");
        var v8Stream =
            getClass()
                .getClassLoader()
                .getResourceAsStream(
                    "db/migration/V8__normalize_script_work_item_plugin_identity.sql")) {
      assertThat(callbackStream).isNotNull();
      assertThat(v8Stream).isNotNull();
      callback = new String(callbackStream.readAllBytes(), StandardCharsets.UTF_8);
      v8 = new String(v8Stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(callback).contains("script_pin_epoch");
    assertThat(v8).contains("UPDATE script_work_items");
  }
}
