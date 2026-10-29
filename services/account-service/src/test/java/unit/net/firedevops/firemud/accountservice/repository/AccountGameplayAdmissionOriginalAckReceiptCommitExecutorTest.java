package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement;
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

/**
 * JDBC/repository/opaque-ACK doubles; not PostgreSQL durability, authentication or admission proof.
 */
class AccountGameplayAdmissionOriginalAckReceiptCommitExecutorTest {
  private static final String WAL_UPPER_FENCE = "0/16B6C50";
  private static final String WAL_COVERAGE_SQL =
      "SELECT current_setting('server_version_num')::integer, pg_is_in_recovery(), "
          + "current_setting('fsync'), pg_current_wal_flush_lsn() >= ?::pg_lsn";

  @AfterEach
  void clearTransaction() {
    for (Object key : TransactionSynchronizationManager.getResourceMap().keySet()) {
      TransactionSynchronizationManager.unbindResource(key);
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void physicalReceiptCommitPrecedesNewConnectionAndExactHistoricalRead() throws Exception {
    var fixture = new Fixture();
    try (var repositories = fixture.repositories()) {
      assertThat(fixture.executor.confirm(fixture.acknowledgement)).isSameAs(fixture.receipt);
      assertThat(repositories.constructed()).hasSize(2);
      var order =
          inOrder(
              fixture.first.connection,
              fixture.second.connection,
              repositories.constructed().get(0),
              repositories.constructed().get(1));
      order.verify(repositories.constructed().get(0)).create(fixture.acknowledgement);
      order.verify(fixture.first.connection).commit();
      order.verify(fixture.first.connection).close();
      order.verify(repositories.constructed().get(1)).readExact(fixture.evidence, fixture.decision);
      order.verify(fixture.second.connection).commit();
      assertIndependentAcquisition(fixture, repositories.constructed().get(1));
      // This is a historical receipt: receipt persistence does not restamp original expiry.
      assertThat(fixture.receipt.expiresAtMs()).isLessThan(System.currentTimeMillis());
      verify(fixture.first.statement).execute("SET LOCAL synchronous_commit = on");
      verify(fixture.second.statement).execute("SET LOCAL synchronous_commit = on");
    }
  }

  @Test
  void creationApiAcceptsOnlyOpaqueAcknowledgement() {
    assertThat(
            java.util.Arrays.stream(
                    AccountGameplayAdmissionOriginalAckReceiptCommitExecutor.class
                        .getDeclaredMethods())
                .filter(method -> method.getName().equals("confirm"))
                .map(method -> java.util.List.of(method.getParameterTypes())))
        .containsExactly(java.util.List.of(OriginalCommitAcknowledgement.class));
  }

  @Test
  void historicalPendingReceiptConstructorRemainsPrivate() {
    var constructors =
        AccountGameplayAdmissionOriginalAckReceiptRepository.PendingHistoricalReceipt.class
            .getDeclaredConstructors();
    assertThat(constructors).hasSize(1);
    assertThat(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
  }

  @Test
  void anotherDataSourceIsRejectedBeforeAnyConnection() throws Exception {
    var fixture = new Fixture();
    var other = mock(DataSource.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(other)
                    .confirm(fixture.acknowledgement))
        .hasMessageContaining("Fresh owned");
    verifyNoInteractions(other);
  }

  @Test
  void missingOpaqueAcknowledgementCannotStartReceiptPersistence() throws Exception {
    var fixture = new Fixture();
    assertThatThrownBy(() -> fixture.executor.confirm(null))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(fixture.source);
  }

  @ParameterizedTest
  @ValueSource(strings = {"xid", "clock"})
  void changedOriginalProofRollsBackWithoutIndependentRead(String fault) throws Exception {
    var fixture = new Fixture();
    if (fault.equals("xid")) {
      when(fixture.acknowledgement.finalizationXid()).thenReturn("987654");
    } else {
      when(fixture.acknowledgement.committedBeforeMs()).thenReturn(1L);
    }
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
          .hasMessageContaining("Fresh owned");
      assertThat(repositories.constructed()).hasSize(1);
      verify(fixture.first.connection, never()).commit();
      verify(fixture.first.connection).rollback();
      verifyNoInteractions(fixture.second.connection);
    }
  }

  @Test
  void retryCommitCannotSubstituteForUnavailableHistoricalFenceCoverage() throws Exception {
    var fixture = new Fixture();
    fixture.inserted = false;
    fixture.flush.covered = false;
    fixture.prepareHistoricalRead(true);
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
          .hasMessageContaining("WAL coverage unavailable");
      verify(fixture.first.connection).commit();
      verify(repositories.constructed().get(1))
          .captureHistorical(fixture.evidence, fixture.decision);
      verify(repositories.constructed().get(1), never()).readExact(any(), any());
      verify(repositories.constructed().get(1), never()).create(any());
      verify(repositories.constructed().get(1), never()).readDurably(any(), any());
      assertThat(repositories.constructed()).hasSize(2);
      verify(fixture.second.connection).commit();
      verify(fixture.second.connection).close();
      verify(fixture.flush.connection).close();
      verifyNoInteractions(fixture.fourth.connection);
    }
  }

  @Test
  void retainedReceiptUsesFrozenFenceCoverageAndExactReread() throws Exception {
    var fixture = new Fixture();
    fixture.inserted = false;
    fixture.prepareHistoricalRead(true);
    try (var repositories = fixture.repositories()) {
      assertThat(fixture.executor.confirm(fixture.acknowledgement)).isSameAs(fixture.receipt);
      var capture = repositories.constructed().get(1);
      var read = repositories.constructed().get(2);
      verify(capture).captureHistorical(fixture.evidence, fixture.decision);
      verify(read).readExact(fixture.evidence, fixture.decision);
      verify(capture, never()).readDurably(any(), any());
      verify(read, never()).create(any());
      assertHistoricalReadSequence(fixture, capture, read, true);
    }
  }

  @Test
  void uncertainPhysicalReceiptCommitNeverStartsIndependentRead() throws Exception {
    var fixture = new Fixture();
    doThrow(new SQLException("COMMIT acknowledgement lost", "08006"))
        .when(fixture.first.connection)
        .commit();
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
          .hasMessageContaining("physical COMMIT unavailable");
      assertThat(repositories.constructed()).hasSize(1);
      verify(fixture.first.connection).rollback();
      verifyNoInteractions(fixture.second.connection);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"failure", "null", "decision", "evidence"})
  void unavailableOrNonExactCreationNeverCommitsOrReads(String fault) throws Exception {
    var fixture = new Fixture();
    try (var repositories = fixture.repositories()) {
      fixture.creationFault = fault;
      assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
          .isInstanceOf(RuntimeException.class);
      assertThat(repositories.constructed()).hasSize(1);
      verify(fixture.first.connection, never()).commit();
      verify(fixture.first.connection).rollback();
      verifyNoInteractions(fixture.second.connection);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"different", "failure", "commit-failure"})
  void readMismatchOrFailureDoesNotExposeCreatedReceipt(String fault) throws Exception {
    var fixture = new Fixture();
    if (fault.equals("different")) {
      var r = fixture.receipt;
      fixture.readReceipt =
          new AccountGameplayAdmissionOriginalAckReceipt(
              r.operation(),
              r.schemaVersion(),
              r.requestId(),
              r.accountId(),
              r.leaseId(),
              r.leaseFence(),
              r.evidenceSha256(),
              r.bindingDecisionId(),
              r.expiresAtMs(),
              r.finalizationXid(),
              r.committedBeforeMs(),
              "123458");
    }
    fixture.failRead = fault.equals("failure");
    if (fault.equals("commit-failure")) {
      doThrow(new SQLException("read commit lost")).when(fixture.second.connection).commit();
    }
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
          .isInstanceOf(RuntimeException.class);
      verify(fixture.first.connection).commit();
      assertThat(repositories.constructed()).hasSize(2);
    }
  }

  @Test
  void poolMayReuseConnectionAfterCompletedCommitAndCleanup() throws Exception {
    var fixture = new Fixture();
    when(fixture.source.getConnection()).thenReturn(fixture.first.connection);
    fixture.readConnection = fixture.first.connection;
    try (var repositories = fixture.repositories()) {
      assertThat(fixture.executor.confirm(fixture.acknowledgement)).isSameAs(fixture.receipt);
      assertThat(repositories.constructed()).hasSize(2);
      var order = inOrder(fixture.first.connection, repositories.constructed().get(1));
      order.verify(fixture.first.connection).commit();
      order.verify(fixture.first.connection).close();
      order.verify(repositories.constructed().get(1)).readExact(fixture.evidence, fixture.decision);
      order.verify(fixture.first.connection).commit();
      assertIndependentAcquisition(fixture, repositories.constructed().get(1));
      verify(fixture.first.connection, never()).rollback();
    }
  }

  @Test
  void readOnlyValidatesExistingReceipt() throws Exception {
    var fixture = new Fixture();
    fixture.prepareHistoricalRead(false);
    try (var repositories = fixture.repositories()) {
      assertThat(fixture.executor.read(fixture.evidence, fixture.decision))
          .isSameAs(fixture.receipt);
      var capture = repositories.constructed().getFirst();
      var read = repositories.constructed().get(1);
      verify(capture, never()).create(any());
      verify(capture).captureHistorical(fixture.evidence, fixture.decision);
      verify(read).readExact(fixture.evidence, fixture.decision);
      verify(capture, never()).readDurably(any(), any());
      verify(fixture.first.connection).commit();
      assertHistoricalReadSequence(fixture, capture, read, false);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"coverage", "sql", "version", "recovery", "fsync", "autocommit"})
  void historicalReadDoesNotReachExactRereadWithoutValidFenceCoverage(String fault)
      throws Exception {
    var fixture = new Fixture();
    fixture.prepareHistoricalRead(false);
    switch (fault) {
      case "coverage" -> fixture.flush.covered = false;
      case "sql" -> fixture.flush.failFenceQuery = true;
      case "version" -> when(fixture.flush.fenceResult.getInt(1)).thenReturn(160000);
      case "recovery" -> when(fixture.flush.fenceResult.getBoolean(2)).thenReturn(true);
      case "fsync" -> when(fixture.flush.fenceResult.getString(3)).thenReturn("off");
      case "autocommit" -> when(fixture.flush.connection.getAutoCommit()).thenReturn(false);
      default -> throw new IllegalArgumentException(fault);
    }
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.read(fixture.evidence, fixture.decision))
          .isInstanceOf(RuntimeException.class);
      assertThat(repositories.constructed()).hasSize(1);
      verify(repositories.constructed().getFirst())
          .captureHistorical(fixture.evidence, fixture.decision);
      verify(repositories.constructed().getFirst(), never()).readExact(any(), any());
      verify(fixture.first.connection).commit();
      verify(fixture.first.connection).close();
      verify(fixture.flush.connection).close();
      verifyNoInteractions(fixture.third.connection);
    }
  }

  @Test
  void historicalReadDoesNotExposeReceiptWhenIndependentExactRereadChanges() throws Exception {
    var fixture = new Fixture();
    fixture.prepareHistoricalRead(false);
    var r = fixture.receipt;
    fixture.readReceipt =
        new AccountGameplayAdmissionOriginalAckReceipt(
            r.operation(),
            r.schemaVersion(),
            r.requestId(),
            r.accountId(),
            r.leaseId(),
            r.leaseFence(),
            r.evidenceSha256(),
            r.bindingDecisionId(),
            r.expiresAtMs(),
            r.finalizationXid(),
            r.committedBeforeMs(),
            "123458");
    try (var repositories = fixture.repositories()) {
      assertThatThrownBy(() -> fixture.executor.read(fixture.evidence, fixture.decision))
          .hasMessageContaining("Fresh owned");
      assertThat(repositories.constructed()).hasSize(2);
      verify(repositories.constructed().get(1)).readExact(fixture.evidence, fixture.decision);
      verify(fixture.third.connection).commit();
      verify(fixture.third.connection, never()).rollback();
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
    assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
        .hasMessageContaining("Fresh owned");
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
      case "version" -> when(jdbc.settings.getInt(1)).thenReturn(160000);
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
      assertThatThrownBy(() -> fixture.executor.confirm(fixture.acknowledgement))
          .isInstanceOf(RuntimeException.class);
      verify(jdbc.connection, never()).commit();
      var order = inOrder(jdbc.connection);
      order.verify(jdbc.connection).rollback();
      order.verify(jdbc.connection).setAutoCommit(true);
      assertThat(repositories.constructed()).hasSize(1);
      verifyNoInteractions(fixture.second.connection);
    }
  }

  private static void assertIndependentAcquisition(
      Fixture fixture, AccountGameplayAdmissionOriginalAckReceiptRepository readRepository) {
    int[] acquisitions = invocationSequences(fixture.source, "getConnection");
    int[] commits = invocationSequences(fixture.first.connection, "commit");
    int[] closes = invocationSequences(fixture.first.connection, "close");
    int[] reads = invocationSequences(readRepository, "readExact");
    assertThat(acquisitions).hasSize(2);
    assertThat(reads).hasSize(1);
    assertThat(commits).isNotEmpty();
    assertThat(closes).isNotEmpty();
    assertThat(acquisitions[0]).isLessThan(commits[0]);
    assertThat(commits[0]).isLessThan(closes[0]);
    assertThat(closes[0]).isLessThan(acquisitions[1]);
    assertThat(acquisitions[1]).isLessThan(reads[0]);
  }

  private static void assertHistoricalReadSequence(
      Fixture fixture,
      AccountGameplayAdmissionOriginalAckReceiptRepository capture,
      AccountGameplayAdmissionOriginalAckReceiptRepository read,
      boolean creationPrecededCapture)
      throws SQLException {
    Jdbc captureJdbc = creationPrecededCapture ? fixture.second : fixture.first;
    Jdbc flushJdbc = fixture.flush;
    Jdbc finalReadJdbc = creationPrecededCapture ? fixture.fourth : fixture.third;
    int[] captureCalls = invocationSequences(capture, "captureHistorical");
    int[] captureCommits = invocationSequences(captureJdbc.connection, "commit");
    int[] captureCloses = invocationSequences(captureJdbc.connection, "close");
    int[] fenceQueries = invocationSequences(flushJdbc.connection, "prepareStatement");
    int[] fenceParameters = invocationSequences(flushJdbc.fenceStatement, "setString");
    int[] fenceExecutions = invocationSequences(flushJdbc.fenceStatement, "executeQuery");
    int[] fenceCloses = invocationSequences(flushJdbc.connection, "close");
    int[] exactReads = invocationSequences(read, "readExact");
    int[] finalCommits = invocationSequences(finalReadJdbc.connection, "commit");

    assertThat(captureCalls).hasSize(1);
    assertThat(captureCommits).hasSize(1);
    assertThat(captureCloses).hasSize(1);
    assertThat(fenceQueries).hasSize(1);
    assertThat(fenceParameters).hasSize(1);
    assertThat(fenceExecutions).hasSize(1);
    assertThat(fenceCloses).hasSize(1);
    assertThat(exactReads).hasSize(1);
    assertThat(finalCommits).hasSize(1);
    assertThat(captureCalls[0]).isLessThan(captureCommits[0]);
    assertThat(captureCommits[0]).isLessThan(captureCloses[0]);
    assertThat(captureCloses[0]).isLessThan(fenceQueries[0]);
    assertThat(fenceQueries[0]).isLessThan(fenceParameters[0]);
    assertThat(fenceParameters[0]).isLessThan(fenceExecutions[0]);
    assertThat(fenceExecutions[0]).isLessThan(fenceCloses[0]);
    assertThat(fenceCloses[0]).isLessThan(exactReads[0]);
    assertThat(exactReads[0]).isLessThan(finalCommits[0]);
    verify(capture).captureHistorical(fixture.evidence, fixture.decision);
    verify(fixture.pending).walUpperFence();
    assertThat(mockingDetails(flushJdbc.connection).getInvocations())
        .filteredOn(invocation -> invocation.getMethod().getName().equals("prepareStatement"))
        .extracting(invocation -> invocation.getArgument(0, String.class))
        .containsExactly(WAL_COVERAGE_SQL);
    verify(flushJdbc.fenceStatement).setString(1, WAL_UPPER_FENCE);
  }

  private static int[] invocationSequences(Object mock, String method) {
    return mockingDetails(mock).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals(method))
        .mapToInt(invocation -> invocation.getSequenceNumber())
        .toArray();
  }

  private static final class Fixture {
    private final DataSource source = mock(DataSource.class);
    private final Jdbc first = new Jdbc();
    private final Jdbc second = new Jdbc();
    private final Jdbc third = new Jdbc();
    private final Jdbc fourth = new Jdbc();
    private final Jdbc flush = new Jdbc();
    private Connection readConnection = second.connection;
    private final AccountGameplayAdmissionLeaseEvidence evidence =
        AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    private final UUID decision = UUID.randomUUID();
    private final OriginalCommitAcknowledgement acknowledgement =
        mock(OriginalCommitAcknowledgement.class);
    private final AccountGameplayAdmissionOriginalAckReceipt receipt = receipt(evidence, decision);
    // Unit-only mock of the private pending capability; the production constructor is asserted
    // private by historicalPendingReceiptConstructorRemainsPrivate.
    private final AccountGameplayAdmissionOriginalAckReceiptRepository.PendingHistoricalReceipt
        pending =
            mock(
                AccountGameplayAdmissionOriginalAckReceiptRepository.PendingHistoricalReceipt
                    .class);
    private boolean inserted = true;
    private AccountGameplayAdmissionOriginalAckReceipt readReceipt = receipt;
    private boolean failRead;
    private String creationFault;
    private boolean historicalRead;
    private boolean historicalAfterCreate;
    private final AccountGameplayAdmissionOriginalAckReceiptCommitExecutor executor =
        new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(source);

    private Fixture() throws SQLException {
      when(source.getConnection()).thenReturn(first.connection, second.connection);
      when(pending.receipt()).thenReturn(receipt);
      when(pending.walUpperFence()).thenReturn(WAL_UPPER_FENCE);
      when(acknowledgement.belongsTo(source)).thenReturn(true);
      when(acknowledgement.evidence()).thenReturn(evidence);
      when(acknowledgement.decisionId()).thenReturn(decision);
      when(acknowledgement.finalizationXid()).thenReturn(receipt.finalizationXid());
      when(acknowledgement.committedBeforeMs()).thenReturn(receipt.committedBeforeMs());
    }

    private void prepareHistoricalRead(boolean afterCreate) throws SQLException {
      historicalRead = true;
      historicalAfterCreate = afterCreate;
      if (afterCreate) {
        when(source.getConnection())
            .thenReturn(first.connection, second.connection, flush.connection, fourth.connection);
      } else {
        when(source.getConnection())
            .thenReturn(first.connection, flush.connection, third.connection);
      }
    }

    private MockedConstruction<AccountGameplayAdmissionOriginalAckReceiptRepository>
        repositories() {
      return mockConstruction(
          AccountGameplayAdmissionOriginalAckReceiptRepository.class,
          (repository, context) -> {
            DSLContext dsl = (DSLContext) context.arguments().getFirst();
            Connection enlisted = dsl.connectionResult(value -> value);
            assertThat(enlisted).isSameAs(repositoryConnection(context.getCount()));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            when(repository.create(acknowledgement))
                .thenAnswer(
                    ignored -> {
                      if (creationFault == null)
                        return new AccountGameplayAdmissionOriginalAckReceiptRepository.Creation(
                            receipt, inserted);
                      AccountGameplayAdmissionOriginalAckReceipt value =
                          switch (creationFault) {
                            case "failure" ->
                                throw new IllegalStateException("creation unavailable");
                            case "null" -> null;
                            case "decision" -> receipt(evidence, UUID.randomUUID());
                            case "evidence" -> {
                              var carrier = new java.util.LinkedHashMap<>(evidence.carrier());
                              carrier.put("requestId", UUID.randomUUID().toString());
                              yield receipt(
                                  AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier),
                                  decision);
                            }
                            default -> throw new IllegalArgumentException(creationFault);
                          };
                      return new AccountGameplayAdmissionOriginalAckReceiptRepository.Creation(
                          value, inserted);
                    });
            when(repository.readExact(evidence, decision)).thenAnswer(ignored -> readResult());
            when(repository.readDurably(evidence, decision)).thenAnswer(ignored -> readResult());
            when(repository.captureHistorical(evidence, decision)).thenReturn(pending);
          });
    }

    private Connection repositoryConnection(int count) {
      if (!historicalRead) return count == 1 ? first.connection : readConnection;
      if (historicalAfterCreate) {
        return switch (count) {
          case 1 -> first.connection;
          case 2 -> second.connection;
          case 3 -> fourth.connection;
          default -> throw new IllegalArgumentException("Unexpected repository " + count);
        };
      }
      return switch (count) {
        case 1 -> first.connection;
        case 2 -> third.connection;
        default -> throw new IllegalArgumentException("Unexpected repository " + count);
      };
    }

    private AccountGameplayAdmissionOriginalAckReceipt readResult() {
      if (failRead) throw new IllegalStateException("read unavailable");
      return readReceipt;
    }
  }

  private static AccountGameplayAdmissionOriginalAckReceipt receipt(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    return AccountGameplayAdmissionOriginalAckReceiptTestFixtures.receipt(evidence, decision);
  }

  private static final class Jdbc {
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet settings = mock(ResultSet.class);
    private final ResultSet fenceResult = mock(ResultSet.class);
    private boolean covered = true;
    private boolean failFenceQuery;
    private final PreparedStatement fenceStatement =
        mock(
            PreparedStatement.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("executeQuery")) {
                if (failFenceQuery) throw new SQLException("flush observation unavailable");
                return fenceResult;
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
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
                switch (invocation.getMethod().getName()) {
                  case "createStatement" -> statement;
                  case "prepareStatement" -> fenceStatement;
                  default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                });
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
      when(settings.getInt(1)).thenReturn(180011);
      when(settings.getString(3)).thenReturn("on");
      when(settings.getString(4)).thenReturn("on");
      when(settings.getString(5)).thenReturn("serializable");
      when(settings.getString(6)).thenReturn("off");
      when(fenceResult.next())
          .thenAnswer(
              new org.mockito.stubbing.Answer<Boolean>() {
                private final AtomicInteger row = new AtomicInteger();

                @Override
                public Boolean answer(org.mockito.invocation.InvocationOnMock ignored) {
                  return row.getAndIncrement() % 2 == 0;
                }
              });
      when(fenceResult.getInt(1)).thenReturn(180011);
      when(fenceResult.getBoolean(2)).thenReturn(false);
      when(fenceResult.getString(3)).thenReturn("on");
      when(fenceResult.getBoolean(4)).thenAnswer(ignored -> covered);
    }
  }
}
