package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectConditionStep;
import org.jooq.SelectWhereStep;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldAuthoredVersionIdentityCanonicalTargetReadTest {
  private final DSLContext dsl = mock(DSLContext.class);

  @AfterEach
  void clearTransactionFixture() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void canonicalTargetReadRejectsAmbientTransactionBeforeSql() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var repository = new WorldAuthoredVersionIdentityRepository(dsl);

    assertThatThrownBy(
            () ->
                repository.readByCanonicalTarget(
                    "firemud",
                    UUID.fromString("22222222-2222-4222-8222-222222222222"),
                    UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    42L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent committed owner read");

    verifyNoInteractions(dsl);
  }

  @Test
  @SuppressWarnings("unchecked")
  void canonicalTargetReadRejectsMultipleWorldAssociationsAsAmbiguous() {
    SelectWhereStep<Record> selectedTable = mock(SelectWhereStep.class);
    SelectConditionStep<Record> selectedTarget = mock(SelectConditionStep.class);
    Result<Record> rows = mock(Result.class);
    doReturn(selectedTable).when(dsl).selectFrom(any(Table.class));
    when(selectedTable.where(any(Condition.class))).thenReturn(selectedTarget);
    when(selectedTarget.fetch()).thenReturn(rows);
    when(rows.isEmpty()).thenReturn(false);
    when(rows.size()).thenReturn(2);
    var repository = new WorldAuthoredVersionIdentityRepository(dsl);

    assertThatThrownBy(
            () ->
                repository.readByCanonicalTarget(
                    "firemud",
                    UUID.fromString("22222222-2222-4222-8222-222222222222"),
                    UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    42L))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class)
        .hasMessageContaining("ambiguous World associations");
  }

  @Test
  @SuppressWarnings("unchecked")
  void canonicalTargetReadReturnsEmptyWhenNoExactAssociationExists() {
    SelectWhereStep<Record> selectedTable = mock(SelectWhereStep.class);
    SelectConditionStep<Record> selectedTarget = mock(SelectConditionStep.class);
    Result<Record> rows = mock(Result.class);
    doReturn(selectedTable).when(dsl).selectFrom(any(Table.class));
    when(selectedTable.where(any(Condition.class))).thenReturn(selectedTarget);
    when(selectedTarget.fetch()).thenReturn(rows);
    when(rows.isEmpty()).thenReturn(true);
    var repository = new WorldAuthoredVersionIdentityRepository(dsl);

    assertThat(
            repository.readByCanonicalTarget(
                "firemud",
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                42L))
        .isEmpty();
  }
}
