package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionCommitConfirmation;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Concrete Spring transaction lifecycle with JDBC/repository doubles; not PostgreSQL proof. */
class AccountGameplayAdmissionReceiptCommitExecutorTest {
  @AfterEach
  void clearTransaction() {
    for (Object key : TransactionSynchronizationManager.getResourceMap().keySet()) {
      TransactionSynchronizationManager.unbindResource(key);
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readOnlyValidatesExistingReceipt() throws Exception {
    var fixture = new Fixture();
    try (var repositories = fixture.repositories()) {
      assertThat(fixture.executor.read(fixture.evidence, fixture.decision))
          .isSameAs(fixture.receipt);
      verify(repositories.constructed().getFirst())
          .readCommitConfirmation(fixture.evidence, fixture.decision);
      verify(fixture.first.connection).commit();
      verifyNoInteractions(fixture.second.connection);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "failure", "decision", "evidence", "commit-failure"})
  void unavailableOrNonExactHistoricalReadNeverReturnsProof(String fault) throws Exception {
    var fixture = new Fixture();
    switch (fault) {
      case "null" -> fixture.readReceipt = null;
      case "failure" -> fixture.failRead = true;
      case "decision" ->
          fixture.readReceipt =
              AccountGameplayAdmissionCommitConfirmationOwnerTest.receipt(
                  fixture.evidence, UUID.randomUUID());
      case "evidence" -> {
        var carrier = new java.util.LinkedHashMap<>(fixture.evidence.carrier());
        carrier.put("requestId", UUID.randomUUID().toString());
        fixture.readReceipt =
            AccountGameplayAdmissionCommitConfirmationOwnerTest.receipt(
                AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier), fixture.decision);
      }
      case "commit-failure" ->
          doThrow(new SQLException("historical read COMMIT unavailable"))
              .when(fixture.first.connection)
              .commit();
      default -> throw new IllegalArgumentException(fault);
    }
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.read(fixture.evidence, fixture.decision))
          .isInstanceOf(RuntimeException.class);
      assertThat(repositories.constructed()).hasSize(1);
      verify(fixture.first.connection).rollback();
      verifyNoInteractions(fixture.second.connection);
      if (!fault.equals("commit-failure")) verify(fixture.first.connection, never()).commit();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"actual", "synchronization", "bound"})
  void ambientContextDeniesBeforeConnectionAcquisition(String ambient) throws Exception {
    var fixture = new Fixture();
    switch (ambient) {
      case "actual" -> TransactionSynchronizationManager.setActualTransactionActive(true);
      case "synchronization" -> TransactionSynchronizationManager.initSynchronization();
      case "bound" ->
          TransactionSynchronizationManager.bindResource(
              fixture.source, new ConnectionHolder(fixture.first.connection));
      default -> throw new IllegalArgumentException(ambient);
    }
    assertThatThrownBy(() -> fixture.executor.read(fixture.evidence, fixture.decision))
        .hasMessageContaining("Fresh owned");
    verifyNoInteractions(fixture.source, fixture.first.connection);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "version",
        "recovery",
        "fsync",
        "sync",
        "isolation",
        "readonly",
        "engine",
        "jdbc-isolation",
        "jdbc-readonly",
        "jdbc-autocommit",
        "set-failure"
      })
  void invalidDurabilityRollsBackBeforeAutoCommitCleanup(String fault) throws Exception {
    var fixture = new Fixture();
    var jdbc = fixture.first;
    switch (fault) {
      case "version" -> when(jdbc.settings.getInt(1)).thenReturn(170000);
      case "recovery" -> when(jdbc.settings.getBoolean(2)).thenReturn(true);
      case "fsync" -> when(jdbc.settings.getString(3)).thenReturn("off");
      case "sync" -> when(jdbc.settings.getString(4)).thenReturn("off");
      case "isolation" -> when(jdbc.settings.getString(5)).thenReturn("read committed");
      case "readonly" -> when(jdbc.settings.getString(6)).thenReturn("on");
      case "engine" -> when(jdbc.metadata.getDatabaseProductName()).thenReturn("Other");
      case "jdbc-isolation" -> jdbc.badIsolation = true;
      case "jdbc-readonly" -> when(jdbc.connection.isReadOnly()).thenReturn(true);
      case "jdbc-autocommit" -> jdbc.badAutoCommit = true;
      case "set-failure" ->
          doThrow(new SQLException("SET rejected"))
              .when(jdbc.statement)
              .execute("SET LOCAL synchronous_commit = on");
      default -> throw new IllegalArgumentException(fault);
    }
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.read(fixture.evidence, fixture.decision))
          .isInstanceOf(RuntimeException.class);
      verify(jdbc.connection, never()).commit();
      var order = inOrder(jdbc.connection);
      order.verify(jdbc.connection).rollback();
      order.verify(jdbc.connection).setAutoCommit(true);
      assertThat(repositories.constructed()).hasSize(1);
      verifyNoInteractions(fixture.second.connection);
    }
  }

  private static final class Fixture {
    private final DataSource source = mock(DataSource.class);
    private final Jdbc first = new Jdbc();
    private final Jdbc second = new Jdbc();
    private Connection readConnection = second.connection;
    private final AccountGameplayAdmissionLeaseEvidence evidence =
        AccountGameplayAdmissionAbortOwnerTest.fixture();
    private final UUID decision = UUID.randomUUID();
    private final AccountGameplayAdmissionCommitConfirmation receipt =
        AccountGameplayAdmissionCommitConfirmationOwnerTest.receipt(evidence, decision);
    private AccountGameplayAdmissionCommitConfirmation readReceipt = receipt;
    private boolean failRead;
    private final AccountGameplayAdmissionReceiptCommitExecutor executor =
        new AccountGameplayAdmissionReceiptCommitExecutor(source);

    private Fixture() throws SQLException {
      when(source.getConnection()).thenReturn(first.connection, second.connection);
    }

    private MockedConstruction<AccountGameplayAdmissionCommitConfirmationRepository>
        repositories() {
      return mockConstruction(
          AccountGameplayAdmissionCommitConfirmationRepository.class,
          (repository, context) -> {
            DSLContext dsl = (DSLContext) context.arguments().getFirst();
            Connection enlisted = dsl.connectionResult(value -> value);
            assertThat(enlisted)
                .isSameAs(context.getCount() == 1 ? first.connection : readConnection);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            when(repository.readCommitConfirmation(evidence, decision))
                .thenAnswer(
                    ignored -> {
                      if (failRead) throw new IllegalStateException("read unavailable");
                      return readReceipt;
                    });
          });
    }
  }

  private static final class Jdbc {
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet settings = mock(ResultSet.class);
    private final Statement statement =
        mock(
            Statement.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("executeQuery")) {
                when(settings.next()).thenReturn(true, false);
                return settings;
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    private final Connection connection =
        mock(
            Connection.class,
            invocation ->
                invocation.getMethod().getName().equals("createStatement")
                    ? statement
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    private boolean badIsolation;
    private boolean badAutoCommit;

    private Jdbc() throws SQLException {
      var autoCommit = new AtomicBoolean(true);
      var isolation = new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
      when(connection.getAutoCommit()).thenAnswer(ignored -> badAutoCommit || autoCommit.get());
      doAnswer(
              ignored -> {
                autoCommit.set(ignored.getArgument(0));
                return null;
              })
          .when(connection)
          .setAutoCommit(anyBoolean());
      when(connection.getTransactionIsolation())
          .thenAnswer(
              ignored -> badIsolation ? Connection.TRANSACTION_READ_COMMITTED : isolation.get());
      doAnswer(
              ignored -> {
                isolation.set(ignored.getArgument(0));
                return null;
              })
          .when(connection)
          .setTransactionIsolation(anyInt());
      when(connection.getMetaData()).thenReturn(metadata);
      when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
      when(settings.getInt(1)).thenReturn(160011);
      when(settings.getString(3)).thenReturn("on");
      when(settings.getString(4)).thenReturn("on");
      when(settings.getString(5)).thenReturn("serializable");
      when(settings.getString(6)).thenReturn("off");
    }
  }
}
