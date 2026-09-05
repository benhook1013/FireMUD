package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V11__validate_script_work_item_plugin_identityTest {
  @Test
  void addsNonNullPluginPairCoherenceCheckWithoutBlockingExistingRows() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V11__validate_script_work_item_plugin_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ADD CONSTRAINT ck_script_work_items_plugin_pair_coherent CHECK")
        .contains("BTRIM(plugin_id) = '' AND BTRIM(plugin_version_id) = ''")
        .contains("BTRIM(plugin_id) <> ''")
        .contains("BTRIM(plugin_version_id) <> ''")
        .contains("/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */");
  }
}
