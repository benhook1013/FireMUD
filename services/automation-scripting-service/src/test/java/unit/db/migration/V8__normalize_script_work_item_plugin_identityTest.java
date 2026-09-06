package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V8__normalize_script_work_item_plugin_identityTest {
  @Test
  void backfillsLegacyPluginIdentityBeforeEnforcingCanonicalDefaultsAndNotNull()
      throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V8__normalize_script_work_item_plugin_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "UPDATE script_work_items",
            "WHEN plugin_id IS NULL OR plugin_version_id IS NULL THEN ''",
            "ELSE plugin_id",
            "ELSE plugin_version_id",
            "WHERE plugin_id IS NULL OR plugin_version_id IS NULL",
            "ALTER COLUMN plugin_id SET DEFAULT ''",
            "ALTER COLUMN plugin_version_id SET DEFAULT ''",
            "ALTER COLUMN plugin_id SET NOT NULL",
            "ALTER COLUMN plugin_version_id SET NOT NULL");

    int update = migration.indexOf("UPDATE script_work_items");
    int defaults = migration.indexOf("ALTER COLUMN plugin_id SET DEFAULT");
    int pluginVersionDefaults = migration.indexOf("ALTER COLUMN plugin_version_id SET DEFAULT");
    int notNull = migration.indexOf("ALTER COLUMN plugin_id SET NOT NULL");
    int pluginVersionNotNull = migration.indexOf("ALTER COLUMN plugin_version_id SET NOT NULL");
    assertThat(update).isGreaterThanOrEqualTo(0);
    assertThat(defaults).isGreaterThan(update);
    assertThat(pluginVersionDefaults).isGreaterThan(update);
    assertThat(notNull).isGreaterThan(defaults);
    assertThat(pluginVersionNotNull).isGreaterThan(pluginVersionDefaults);
  }
}
