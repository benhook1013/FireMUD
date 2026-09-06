package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class V17__gameplay_command_recovery_indexTest {

  @Test
  void createsConcurrentIndexWhenMissing() {
    assertThat(
            V17__gameplay_command_recovery_index.statements(
                V17__gameplay_command_recovery_index.IndexState.MISSING))
        .containsExactly(
            "CREATE INDEX CONCURRENTLY idx_gameplay_command_recovery_accepted_unstaged"
                + " ON gameplay_command USING btree (accepted_at, id)"
                + " WHERE execution_outcome = 'ACCEPTED' AND staged_at IS NULL");
  }

  @Test
  void replacesInvalidIndexWithConcurrentIndex() {
    List<String> statements =
        V17__gameplay_command_recovery_index.statements(
            V17__gameplay_command_recovery_index.IndexState.INVALID);

    assertThat(statements)
        .containsExactly(
            "DROP INDEX CONCURRENTLY IF EXISTS idx_gameplay_command_recovery_accepted_unstaged",
            "CREATE INDEX CONCURRENTLY idx_gameplay_command_recovery_accepted_unstaged"
                + " ON gameplay_command USING btree (accepted_at, id)"
                + " WHERE execution_outcome = 'ACCEPTED' AND staged_at IS NULL");
  }

  @Test
  void leavesExpectedIndexUntouched() {
    assertThat(
            V17__gameplay_command_recovery_index.statements(
                V17__gameplay_command_recovery_index.IndexState.EXPECTED))
        .isEmpty();
  }

  @Test
  void classifiesValidNonUniquePartialIndexAsExpected() {
    assertThat(
            V17__gameplay_command_recovery_index.classifyCatalogRow(
                true,
                true,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                "((execution_outcome)::text = 'ACCEPTED'::text) AND (staged_at IS NULL)"))
        .isEqualTo(V17__gameplay_command_recovery_index.IndexState.EXPECTED);
  }

  @Test
  void classifiesUniqueIndexAsInvalid() {
    assertThat(
            V17__gameplay_command_recovery_index.classifyCatalogRow(
                true, true, true, true, true, true, true, true, true, "ignored"))
        .isEqualTo(V17__gameplay_command_recovery_index.IndexState.INVALID);
  }

  @Test
  void doesNotTreatConflictingObjectAsRepairableIndex() {
    assertThatThrownBy(
            () ->
                V17__gameplay_command_recovery_index.statements(
                    V17__gameplay_command_recovery_index.IndexState.CONFLICTING_OBJECT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owned by another table or is not an index");
  }

  @Test
  void runsOutsideFlywayTransaction() {
    assertThat(new V17__gameplay_command_recovery_index().canExecuteInTransaction()).isFalse();
  }
}
