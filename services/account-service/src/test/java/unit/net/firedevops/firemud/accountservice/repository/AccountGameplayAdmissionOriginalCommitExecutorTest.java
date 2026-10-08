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
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Concrete Spring/JDBC lifecycle with mocked JDBC responses and repository storage. These unit
 * doubles prove neither physical PostgreSQL durability nor a persisted receipt, authentication or
 * admission.
 */
class AccountGameplayAdmissionOriginalCommitExecutorTest {
  @AfterEach
  void clearTransaction() {
    for (Object key : TransactionSynchronizationManager.getResourceMap().keySet()) {
      TransactionSynchronizationManager.unbindResource(key);
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void actualJdbcCommitPrecedesOpaqueAcknowledgement() throws Exception {
    var fixture = new Fixture();
    var returned =
        new AtomicReference<
            AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement>();
    doAnswer(
            ignored -> {
              assertThat(returned.get()).isNull();
              return null;
            })
        .when(fixture.connection)
        .commit();
    try (var repositories = fixture.repositories(State.PENDING)) {
      returned.set(fixture.executor.execute(fixture.evidence, fixture.decision));
      var acknowledgement = returned.get();
      assertThat(acknowledgement.evidence()).isSameAs(fixture.evidence);
      assertThat(acknowledgement.decisionId()).isEqualTo(fixture.decision);
      assertThat(acknowledgement.finalizationXid()).isEqualTo("42");
      assertThat(acknowledgement.committedBeforeMs()).isEqualTo(9000L);
      assertThat(acknowledgement.belongsTo(fixture.source)).isTrue();
      assertThat(acknowledgement.belongsTo(mock(DataSource.class))).isFalse();
      assertThat(acknowledgement.belongsTo(null)).isFalse();
      assertThat(Serializable.class.isAssignableFrom(acknowledgement.getClass())).isFalse();
      assertThat(Arrays.stream(acknowledgement.getClass().getDeclaredConstructors()))
          .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
      var order =
          inOrder(
              repositories.constructed().getFirst(),
              fixture.stamp,
              fixture.settingsStatement,
              fixture.connection,
              fixture.clockStatement);
      order
          .verify(repositories.constructed().getFirst())
          .recordCommitted(fixture.evidence, fixture.decision);
      // Verify completed query lifecycles without invoking JDBC resource factories on verify mocks.
      order.verify(fixture.stamp).close();
      order.verify(fixture.settingsStatement).execute("SET LOCAL synchronous_commit = on");
      order.verify(fixture.settingsStatement).close();
      order.verify(fixture.connection).commit();
      order.verify(fixture.clockStatement).close();
      var commits =
          mockingDetails(fixture.connection).getInvocations().stream()
              .filter(invocation -> invocation.getMethod().getName().equals("commit"))
              .mapToInt(invocation -> invocation.getSequenceNumber())
              .toArray();
      var clockQueries =
          mockingDetails(fixture.clockStatement).getInvocations().stream()
              .filter(invocation -> invocation.getMethod().getName().equals("executeQuery"))
              .mapToInt(invocation -> invocation.getSequenceNumber())
              .toArray();
      assertThat(commits).hasSize(1);
      assertThat(clockQueries).hasSize(1);
      assertThat(clockQueries[0]).isGreaterThan(commits[0]);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
  }

  @Test
  void acknowledgementRetainsConfiguredSourceIdentityWhenSpringUnwrapsProxy() throws Exception {
    var fixture = new Fixture();
    var configured = new TransactionAwareDataSourceProxy(fixture.source);
    try (var repositories = fixture.repositories(State.PENDING)) {
      var acknowledgement =
          new AccountGameplayAdmissionOriginalCommitExecutor(configured)
              .execute(fixture.evidence, fixture.decision);
      assertThat(acknowledgement.belongsTo(configured)).isTrue();
      assertThat(acknowledgement.belongsTo(fixture.source)).isFalse();
      assertThat(repositories.constructed()).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"10000", "11000", "0", "-1", "9223372036854775808", "9000.5"})
  void invalidOrLatePostCommitClockCannotReturnCapability(String value) throws Exception {
    var fixture = new Fixture();
    when(fixture.clockRow.getBigDecimal(1)).thenReturn(new BigDecimal(value));
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("post-COMMIT clock bound unavailable");
      verify(fixture.connection).commit();
      verify(fixture.connection).rollback();
      verify(repositories.constructed().getFirst())
          .recordCommitted(fixture.evidence, fixture.decision);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "null", "query-error"})
  void unavailableClockAfterAcknowledgedCommitCannotReturnCapability(String fault)
      throws Exception {
    var fixture = new Fixture();
    switch (fault) {
      case "missing" -> when(fixture.clockRow.next()).thenReturn(false);
      case "null" -> when(fixture.clockRow.getBigDecimal(1)).thenReturn(null);
      case "query-error" -> fixture.clockFailure = new SQLException("clock response lost", "08006");
      default -> throw new IllegalArgumentException(fault);
    }
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("post-COMMIT clock");
      verify(fixture.connection).commit();
      verify(fixture.connection).rollback();
      verify(repositories.constructed().getFirst())
          .recordCommitted(fixture.evidence, fixture.decision);
    }
  }

  @Test
  void ambiguousPhysicalCommitThrowsWithoutReturningAcknowledgement() throws Exception {
    var fixture = new Fixture();
    doThrow(new SQLException("connection lost during COMMIT", "08006"))
        .when(fixture.connection)
        .commit();
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("physical COMMIT unavailable");
      verify(fixture.connection).commit();
      verify(fixture.connection).rollback();
      assertThat(repositories.constructed()).hasSize(1);
    }
  }

  @Test
  void failureToEnforceSynchronousCommitRollsBackBeforeConnectionCleanup() throws Exception {
    var fixture = new Fixture();
    doThrow(new SQLException("SET LOCAL rejected"))
        .when(fixture.settingsStatement)
        .execute("SET LOCAL synchronous_commit = on");
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("physical COMMIT unavailable");
      verify(fixture.connection, never()).commit();
      var order = inOrder(fixture.settingsStatement, fixture.connection);
      order.verify(fixture.settingsStatement).execute("SET LOCAL synchronous_commit = on");
      order.verify(fixture.connection).rollback();
      order.verify(fixture.connection).setAutoCommit(true);
      assertThat(repositories.constructed()).hasSize(1);
    }
  }

  @Test
  void retainedCommittedRetryCannotMintAnotherAcknowledgement() throws Exception {
    var fixture = new Fixture();
    try (var repositories = fixture.repositories(State.COMMITTED)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("Fresh owned Account original COMMIT required");
      verify(repositories.constructed().getFirst(), never())
          .recordCommitted(fixture.evidence, fixture.decision);
      verify(fixture.connection, never()).commit();
      verify(fixture.connection).rollback();
      verifyNoInteractions(fixture.stamp, fixture.settingsStatement);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"actual", "synchronization", "bound"})
  void ambientContextDeniesBeforeAcquiringConnection(String ambient) throws Exception {
    var fixture = new Fixture();
    switch (ambient) {
      case "actual" -> TransactionSynchronizationManager.setActualTransactionActive(true);
      case "synchronization" -> TransactionSynchronizationManager.initSynchronization();
      case "bound" ->
          TransactionSynchronizationManager.bindResource(
              fixture.source, new ConnectionHolder(fixture.connection));
      default -> throw new IllegalArgumentException(ambient);
    }
    assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
    verifyNoInteractions(fixture.source, fixture.connection);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"version", "recovery", "fsync", "sync", "isolation", "readonly", "engine"})
  void unsupportedDurabilityAtCommitDenies(String fault) throws Exception {
    var fixture = new Fixture();
    switch (fault) {
      case "version" -> when(fixture.settings.getInt(1)).thenReturn(170000);
      case "recovery" -> when(fixture.settings.getBoolean(2)).thenReturn(true);
      case "fsync" -> when(fixture.settings.getString(3)).thenReturn("off");
      case "sync" -> when(fixture.settings.getString(4)).thenReturn("off");
      case "isolation" -> when(fixture.settings.getString(5)).thenReturn("read committed");
      case "readonly" -> when(fixture.settings.getString(6)).thenReturn("on");
      case "engine" -> when(fixture.metadata.getDatabaseProductName()).thenReturn("Other");
      default -> throw new IllegalArgumentException(fault);
    }
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("Fresh owned Account original COMMIT required");
      verify(fixture.connection, never()).commit();
      verify(fixture.connection).rollback();
      assertThat(repositories.constructed()).hasSize(1);
    }
  }

  @Test
  void finalizationStampMustBelongToActualOriginalTransaction() throws Exception {
    var fixture = new Fixture();
    when(fixture.stampRow.getString(2)).thenReturn("43");
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("Fresh owned Account original COMMIT required");
      verify(fixture.connection, never()).commit();
      verify(fixture.connection).rollback();
      assertThat(repositories.constructed()).hasSize(1);
    }
  }

  @Test
  void replacingEnlistedConnectionCannotCommitOriginalBinding() throws Exception {
    var fixture = new Fixture();
    Connection different = mock(Connection.class);
    var calls = new AtomicInteger();
    when(fixture.stampRow.next())
        .thenAnswer(
            ignored -> {
              if (calls.getAndIncrement() == 0) return true;
              TransactionSynchronizationManager.unbindResource(fixture.source);
              TransactionSynchronizationManager.bindResource(
                  fixture.source, new ConnectionHolder(different));
              return false;
            });
    try (var repositories = fixture.repositories(State.PENDING)) {
      assertThatThrownBy(() -> fixture.executor.execute(fixture.evidence, fixture.decision))
          .hasMessageContaining("Fresh owned Account original COMMIT required");
      verify(fixture.connection, never()).commit();
      verify(different, never()).commit();
      assertThat(repositories.constructed()).hasSize(1);
    }
  }

  private static final class Fixture {
    private final DataSource source = mock(DataSource.class);
    private final ResultSet clockRow = mock(ResultSet.class);
    private SQLException clockFailure;
    private final Statement clockStatement =
        mock(
            Statement.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("executeQuery")) {
                assertThat(invocation.getArgument(0, String.class))
                    .isEqualTo("SELECT ceil(extract(epoch FROM clock_timestamp()) * 1000)::bigint");
                if (clockFailure != null) throw clockFailure;
                return clockRow;
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    private final AtomicInteger statementCalls = new AtomicInteger();
    private final ResultSet settings = mock(ResultSet.class);
    private final Statement settingsStatement =
        mock(
            Statement.class,
            invocation ->
                invocation.getMethod().getName().equals("executeQuery")
                    ? settings
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final ResultSet stampRow = mock(ResultSet.class);
    private final PreparedStatement stamp =
        mock(
            PreparedStatement.class,
            invocation ->
                invocation.getMethod().getName().equals("executeQuery")
                    ? stampRow
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final Connection connection =
        mock(
            Connection.class,
            invocation ->
                switch (invocation.getMethod().getName()) {
                  case "createStatement" ->
                      statementCalls.getAndIncrement() == 0 ? settingsStatement : clockStatement;
                  case "prepareStatement" -> stamp;
                  default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                });
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final AccountGameplayAdmissionLeaseEvidence evidence =
        mock(AccountGameplayAdmissionLeaseEvidence.class);
    private final UUID decision = UUID.randomUUID();
    private final AccountGameplayAdmissionOriginalCommitExecutor executor =
        new AccountGameplayAdmissionOriginalCommitExecutor(source);

    private Fixture() throws SQLException {
      var autoCommit = new AtomicBoolean(true);
      var isolation = new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
      when(source.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenAnswer(ignored -> autoCommit.get());
      doAnswer(
              ignored -> {
                autoCommit.set(ignored.getArgument(0));
                return null;
              })
          .when(connection)
          .setAutoCommit(anyBoolean());
      when(connection.getTransactionIsolation()).thenAnswer(ignored -> isolation.get());
      doAnswer(
              ignored -> {
                isolation.set(ignored.getArgument(0));
                return null;
              })
          .when(connection)
          .setTransactionIsolation(anyInt());
      when(connection.getMetaData()).thenReturn(metadata);
      when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
      when(stampRow.next()).thenReturn(true, false);
      when(stampRow.getString(1)).thenReturn("42");
      when(stampRow.getString(2)).thenReturn("42");
      when(settings.next()).thenReturn(true, false);
      when(settings.getInt(1)).thenReturn(160011);
      when(settings.getString(3)).thenReturn("on");
      when(settings.getString(4)).thenReturn("on");
      when(settings.getString(5)).thenReturn("serializable");
      when(settings.getString(6)).thenReturn("off");
      when(clockRow.next()).thenReturn(true, false);
      when(clockRow.getBigDecimal(1)).thenReturn(BigDecimal.valueOf(9000L));
      when(evidence.carrier())
          .thenReturn(
              Map.of(
                  "requestId", UUID.randomUUID().toString(),
                  "leaseId", UUID.randomUUID().toString(),
                  "expiresAt", "10000"));
      when(evidence.sha256()).thenReturn("a".repeat(64));
      when(evidence.canonicalJson()).thenReturn("synthetic-unit-carrier");
    }

    private MockedConstruction<AccountGameplayAdmissionLeaseRepository> repositories(State prior) {
      return mockConstruction(
          AccountGameplayAdmissionLeaseRepository.class,
          (repository, context) -> {
            DSLContext dsl = (DSLContext) context.arguments().getFirst();
            Connection enlisted = dsl.connectionResult(value -> value);
            assertThat(enlisted).isSameAs(connection);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            when(repository.readExact(evidence))
                .thenReturn(
                    Optional.of(
                        new AccountGameplayAdmissionLeaseOperation(
                            evidence, prior, prior == State.COMMITTED ? decision : null, null)));
            when(repository.recordCommitted(evidence, decision))
                .thenReturn(
                    new AccountGameplayAdmissionLeaseOperation(
                        evidence, State.COMMITTED, decision, null));
          });
    }
  }
}
