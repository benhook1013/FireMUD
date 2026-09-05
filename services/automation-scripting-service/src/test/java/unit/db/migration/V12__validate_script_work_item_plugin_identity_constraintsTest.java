package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V12__validate_script_work_item_plugin_identity_constraintsTest {
  @Test
  void validatesThePluginPairConstraintAfterAdditiveInstallation() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V12__validate_script_work_item_plugin_identity_constraints.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("VALIDATE CONSTRAINT ck_script_work_items_plugin_pair_coherent")
        .doesNotContain("NOT VALID");
  }
}
