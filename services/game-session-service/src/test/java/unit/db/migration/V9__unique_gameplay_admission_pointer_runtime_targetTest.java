package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class V9__unique_gameplay_admission_pointer_runtime_targetTest {

  @Test
  void createsConcurrentIndexForMissingTarget() {
    List<String> statements =
        V9__unique_gameplay_admission_pointer_runtime_target.statements(
            V9__unique_gameplay_admission_pointer_runtime_target.IndexState.MISSING);

    assertThat(statements)
        .containsExactly(
            "CREATE UNIQUE INDEX CONCURRENTLY uq_gameplay_admission_pointer_runtime_target"
                + " ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id)");
  }

  @Test
  void replacesInvalidTargetWithConcurrentIndex() {
    List<String> statements =
        V9__unique_gameplay_admission_pointer_runtime_target.statements(
            V9__unique_gameplay_admission_pointer_runtime_target.IndexState.INVALID);

    assertThat(statements)
        .containsExactly(
            "DROP INDEX CONCURRENTLY IF EXISTS uq_gameplay_admission_pointer_runtime_target",
            "CREATE UNIQUE INDEX CONCURRENTLY uq_gameplay_admission_pointer_runtime_target"
                + " ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id)");
  }

  @Test
  void leavesExpectedIndexUntouched() {
    assertThat(
            V9__unique_gameplay_admission_pointer_runtime_target.statements(
                V9__unique_gameplay_admission_pointer_runtime_target.IndexState.EXPECTED))
        .isEmpty();
  }

  @Test
  void runsOutsideFlywayTransaction() {
    assertThat(new V9__unique_gameplay_admission_pointer_runtime_target().canExecuteInTransaction())
        .isFalse();
  }
}
