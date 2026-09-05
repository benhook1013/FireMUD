package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V8__stable_script_definition_identityTest {
  @Test
  void addsNaturalKeyWithoutSilentlyDeletingRetainedDuplicates() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V8__stable_script_definition_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("uq_scripts_definition_identity")
        .contains("tenant_id, version, name")
        .contains("DO $$")
        .contains("FROM scripts")
        .contains("HAVING COUNT(*) > 1")
        .contains("V8 cannot install scripts definition identity: retained duplicate rows exist")
        .contains("retained data")
        .doesNotContain("DELETE FROM scripts")
        .doesNotContain("DROP TABLE scripts")
        .containsPattern(
            "(?s)DO \\$\\$.*?RAISE EXCEPTION 'V8 cannot install scripts definition identity: "
                + "retained duplicate rows exist';.*?END \\$\\$;")
        .containsPattern(
            "(?s)FROM scripts\\s+GROUP BY tenant_id, version, name\\s+"
                + "HAVING COUNT\\(\\*\\) > 1");

    assertThat(migration.indexOf("END $$;")).isLessThan(migration.indexOf("ALTER TABLE scripts"));
  }
}
