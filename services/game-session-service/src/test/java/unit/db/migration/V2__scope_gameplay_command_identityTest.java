package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V2__scope_gameplay_command_identityTest {

  @Test
  void replacesGlobalCommandIdentityWithScopedUniquenessAndCorrelationIndex() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V2__scope_gameplay_command_identity.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains(
            "DROP INDEX IF EXISTS idx_gameplay_command_command_id",
            "DROP CONSTRAINT IF EXISTS gameplay_command_command_id_key",
            "CREATE UNIQUE INDEX idx_gameplay_command_tenant_instance_command_id ON gameplay_command USING btree (tenant_id, game_instance_id, command_id)",
            "CREATE INDEX idx_gameplay_command_command_id ON gameplay_command USING btree (command_id)",
            "DROP INDEX IF EXISTS idx_remote_command_coordinator_command_id",
            "CREATE UNIQUE INDEX idx_remote_command_coordinator_tenant_origin_instance_command_id ON remote_command_coordinator USING btree (tenant_id, origin_game_instance_id, command_id)",
            "CREATE INDEX idx_remote_command_coordinator_command_id ON remote_command_coordinator USING btree (tenant_id, command_id)",
            "DROP INDEX IF EXISTS uq_gameplay_admission_pointer_world_realm",
            "CREATE UNIQUE INDEX uq_gameplay_admission_pointer_tenant_world_realm ON gameplay_admission_pointer USING btree (tenant_id, world_slug, realm_slug)")
        .doesNotContain("DROP TABLE", "DROP COLUMN");
  }
}
