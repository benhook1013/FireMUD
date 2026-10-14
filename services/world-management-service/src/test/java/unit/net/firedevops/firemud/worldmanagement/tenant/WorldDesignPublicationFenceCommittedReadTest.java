package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.SelectWhereStep;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldDesignPublicationFenceCommittedReadTest {
  private static final UUID PUBLICATION_FENCE =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @AfterEach
  void clearTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
  }

  @Test
  void springProxyReadsWithNoAmbientTransactionOrSynchronization() {
    DSLContext dsl = mockMissingAttemptQuery();
    WorldDesignPublicationFenceRepository repository = proxiedRepository(dsl);

    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(repository.readAttemptByFence(PUBLICATION_FENCE)).isEmpty();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    verify(dsl).selectFrom(any(Table.class));
  }

  @Test
  void rejectsAnAmbientActualTransactionBeforeQuerying() {
    DSLContext dsl = mock(DSLContext.class);
    WorldDesignPublicationFenceRepository repository = proxiedRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readAttemptByFence(PUBLICATION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("World publication attempt read requires a committed read");
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsAnAmbientEmptySynchronizationBeforeQuerying() {
    DSLContext dsl = mock(DSLContext.class);
    WorldDesignPublicationFenceRepository repository = proxiedRepository(dsl);
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> repository.readAttemptByFence(PUBLICATION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("World publication attempt read requires a committed read");
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    verifyNoInteractions(dsl);
  }

  @Test
  void privateKeyLockRejectsMissingOrNonpositiveScopeBeforeQuerying() {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new WorldDesignPublicationFenceRepository(
            dsl, mock(WorldAuthoredSourceIntakeRepository.class));
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(null, 7L))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 0L))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void privateKeyLockRequiresAnActiveWritableOwnerTransactionBeforeQuerying() {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new WorldDesignPublicationFenceRepository(
            dsl, mock(WorldAuthoredSourceIntakeRepository.class));
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 7L))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 7L))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_REPEATABLE_READ);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 7L))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void privateKeyLockRejectsMissingOrFrozenOwnersWithoutCreatingRowsOrReadingIntake() {
    DSLContext dsl = mockWritableOwnerQuery();
    var intake = mock(WorldAuthoredSourceIntakeRepository.class);
    var repository = new WorldDesignPublicationFenceRepository(dsl, intake);
    var query = dsl.selectFrom(any(Table.class)).where(any(Condition.class));
    when(query.forUpdate().fetchOne()).thenReturn(null);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 7L))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("existing publication owner row");
    Record owner = mock(Record.class);
    when(owner.get(any(Field.class), org.mockito.ArgumentMatchers.eq(String.class)))
        .thenReturn("FROZEN");
    when(query.forUpdate().fetchOne()).thenReturn(owner);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 7L))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    verifyNoInteractions(intake);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void privateKeyLockAcceptsOnlyOpenWithoutRetainedFenceAndUsesExactPrivateKeys() {
    DSLContext dsl = mockWritableOwnerQuery();
    var repository =
        new WorldDesignPublicationFenceRepository(
            dsl, mock(WorldAuthoredSourceIntakeRepository.class));
    Record owner = mock(Record.class);
    when(owner.get(any(Field.class), org.mockito.ArgumentMatchers.eq(String.class)))
        .thenReturn("OPEN");
    SelectWhereStep<Record> selectedOwner = mock(SelectWhereStep.class);
    SelectConditionStep<Record> query = mock(SelectConditionStep.class, RETURNS_DEEP_STUBS);
    doReturn(selectedOwner).when(dsl).selectFrom(any(Table.class));
    when(selectedOwner.where(any(Condition.class))).thenReturn(query);
    when(query.forUpdate().fetchOne()).thenReturn(owner);
    repository.lockExistingOpenByPrivateKeys(1L, 7L);
    var condition = org.mockito.ArgumentCaptor.forClass(Condition.class);
    verify(selectedOwner).where(condition.capture());
    assertThat(condition.getValue())
        .isEqualTo(
            org.jooq
                .impl
                .DSL
                .field(org.jooq.impl.DSL.name("local_tenant_key"), Long.class)
                .eq(1L)
                .and(
                    org.jooq
                        .impl
                        .DSL
                        .field(org.jooq.impl.DSL.name("version_id"), Long.class)
                        .eq(7L)));
    when(owner.get(any(Field.class), org.mockito.ArgumentMatchers.eq(UUID.class)))
        .thenReturn(PUBLICATION_FENCE);
    assertThatThrownBy(() -> repository.lockExistingOpenByPrivateKeys(1L, 7L))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static DSLContext mockWritableOwnerQuery() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
    DSLContext dsl = mock(DSLContext.class, RETURNS_DEEP_STUBS);
    Record settings = mock(Record.class);
    when(settings.get("transaction_isolation", String.class)).thenReturn("read committed");
    when(settings.get("transaction_read_only", String.class)).thenReturn("off");
    when(dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only"))
        .thenReturn(settings);
    return dsl;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static DSLContext mockMissingAttemptQuery() {
    DSLContext dsl = mock(DSLContext.class);
    SelectWhereStep<Record> selectedTable = mock(SelectWhereStep.class);
    SelectConditionStep<Record> selectedAttempt = mock(SelectConditionStep.class);
    doReturn(selectedTable).when(dsl).selectFrom(any(Table.class));
    when(selectedTable.where(any(Condition.class))).thenReturn(selectedAttempt);
    when(selectedAttempt.fetchOne()).thenReturn(null);
    return dsl;
  }

  private static WorldDesignPublicationFenceRepository proxiedRepository(DSLContext dsl) {
    var repository =
        new WorldDesignPublicationFenceRepository(
            dsl, mock(WorldAuthoredSourceIntakeRepository.class));
    var transactionInterceptor = new TransactionInterceptor();
    transactionInterceptor.setTransactionManager(
        new DataSourceTransactionManager(mock(DataSource.class)));
    transactionInterceptor.setTransactionAttributeSource(
        new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(repository);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(transactionInterceptor);
    return (WorldDesignPublicationFenceRepository) proxy.getProxy();
  }
}
