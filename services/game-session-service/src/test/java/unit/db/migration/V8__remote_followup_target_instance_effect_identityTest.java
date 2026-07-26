package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class V8__remote_followup_target_instance_effect_identityTest {

  private final V8__remote_followup_target_instance_effect_identity migration =
      new V8__remote_followup_target_instance_effect_identity();

  @Test
  void createsTargetIndexWhenMissing() {
    List<String> statements =
        migration.migrationStatements(
            V8__remote_followup_target_instance_effect_identity.IndexState.MISSING);

    assertThat(statements)
        .hasSize(2)
        .first()
        .asString()
        .startsWith("CREATE UNIQUE INDEX CONCURRENTLY");
    assertThat(statements.get(1))
        .isEqualTo(
            "DROP INDEX CONCURRENTLY IF EXISTS idx_remote_followup_target_region_epoch_effect");
  }

  @Test
  void retainsExpectedTargetIndex() {
    assertThat(
            migration.migrationStatements(
                V8__remote_followup_target_instance_effect_identity.IndexState.EXPECTED))
        .containsExactly(
            "DROP INDEX CONCURRENTLY IF EXISTS idx_remote_followup_target_region_epoch_effect");
  }

  @Test
  void replacesInvalidOrMismatchedTargetIndexBeforeDroppingLegacyIndex() {
    List<String> statements =
        migration.migrationStatements(
            V8__remote_followup_target_instance_effect_identity.IndexState.MISMATCHED);

    assertThat(statements)
        .hasSize(3)
        .startsWith(
            "DROP INDEX CONCURRENTLY IF EXISTS idx_remote_followup_target_instance_region_epoch_effect");
    assertThat(statements.get(1)).startsWith("CREATE UNIQUE INDEX CONCURRENTLY");
    assertThat(statements.get(2))
        .isEqualTo(
            "DROP INDEX CONCURRENTLY IF EXISTS idx_remote_followup_target_region_epoch_effect");
  }
}
