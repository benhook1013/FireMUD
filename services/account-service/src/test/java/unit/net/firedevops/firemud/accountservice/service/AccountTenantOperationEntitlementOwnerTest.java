package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountTenantOperationEntitlementOwner;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantOperationEntitlementOwnerTest {
  @Test
  void demoUsesIndependentWritableSerializableTransactionAndPropagatesFiveSecondJdbcBudget()
      throws Exception {
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    var transactions = spy(new DataSourceTransactionManager(dataSource));
    var demos = mock(AccountDemoTenantEntitlementRepository.class);
    var hosting = mock(AccountHostingSubscriptionLifecycleService.class);
    UUID tenantId = UUID.randomUUID();
    var request =
        new AccountTenantOperationEntitlementOwner.Request(
            tenantId, UUID.randomUUID(), "spiffe://firemud/ns/prod/sa/world-management-service");
    try (var statement = mock(PreparedStatement.class)) {
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true, false);
      when(connection.getTransactionIsolation())
          .thenReturn(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.execute()).thenThrow(new SQLTimeoutException("Controlled timeout", "57014"));
      // The actual Spring proxy carries the transaction timeout to jOOQ's JDBC statement.
      // This driver is controlled; the PostgreSQL suite proves physical connection isolation.
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      when(demos.readCurrent(tenantId))
          .thenAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                    .isFalse();
                dsl.connection(
                    current ->
                        assertThat(current.getTransactionIsolation())
                            .isEqualTo(Connection.TRANSACTION_SERIALIZABLE));
                dsl.execute("SELECT 1");
                throw new AssertionError("The controlled source statement must time out");
              });
      var owner =
          new AccountTenantOperationEntitlementOwner(
              hosting, demos, transactions, Clock.systemUTC(), "prod");

      assertThatThrownBy(() -> owner.evaluateDemo(request))
          .isExactlyInstanceOf(DataAccessException.class)
          .hasRootCauseExactlyInstanceOf(SQLTimeoutException.class);

      var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
      verify(transactions).getTransaction(definition.capture());
      assertThat(definition.getValue().getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getValue().getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThat(definition.getValue().isReadOnly()).isFalse();
      assertThat(definition.getValue().getTimeout()).isEqualTo(5);
      verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      verify(statement).setQueryTimeout(intThat(seconds -> seconds > 0 && seconds <= 5));
      verify(connection).rollback();
      verifyNoInteractions(hosting);
    }
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }
}
