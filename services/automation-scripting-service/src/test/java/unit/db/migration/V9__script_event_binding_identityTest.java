package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V9__script_event_binding_identityTest {
  @Test
  void addsStableBindingIdentityInAForwardMigration() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V9__script_event_binding_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ");
    assertThat(normalized)
        .contains(
            "ALTER TABLE script_event_bindings ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT '';",
            "ALTER TABLE script_event_bindings DROP CONSTRAINT uq_script_event_binding;",
            "script_id, binding_id, target_scope_type",
            "DROP INDEX idx_script_event_bindings_resolution;",
            "script_id, binding_id, id");

    int addColumn = normalized.indexOf("ADD COLUMN binding_id");
    int dropConstraint = normalized.indexOf("DROP CONSTRAINT uq_script_event_binding");
    int addConstraint = normalized.indexOf("ADD CONSTRAINT uq_script_event_binding");
    int dropIndex = normalized.indexOf("DROP INDEX idx_script_event_bindings_resolution");
    int recreateIndex = normalized.indexOf("CREATE INDEX idx_script_event_bindings_resolution");
    assertThat(addColumn).isGreaterThanOrEqualTo(0);
    assertThat(dropConstraint).isGreaterThan(addColumn);
    assertThat(addConstraint).isGreaterThan(addColumn);
    assertThat(addConstraint).isGreaterThan(dropConstraint);
    assertThat(dropIndex).isGreaterThan(addConstraint);
    assertThat(recreateIndex).isGreaterThan(addConstraint);
    assertThat(recreateIndex).isGreaterThan(dropIndex);
  }
}
