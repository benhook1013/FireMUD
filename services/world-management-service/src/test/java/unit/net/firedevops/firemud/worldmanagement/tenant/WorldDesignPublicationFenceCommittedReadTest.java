package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.Condition;
import org.jooq.DSLContext;
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
