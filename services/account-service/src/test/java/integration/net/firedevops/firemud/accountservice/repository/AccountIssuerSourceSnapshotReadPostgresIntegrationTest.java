package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerSourceSnapshotReadOwner;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical PostgreSQL proof for current issuer reads, exact retry fences and writer concurrency.
 */
class AccountIssuerSourceSnapshotReadPostgresIntegrationTest {
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String NAMESPACE = "test";
  private static final String CALLER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String SCHEMA_PREFIX = "account_issuer_read";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void configurePostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopOwnedContainer() {
    POSTGRES.stop();
  }

  @Test
  void genuineEnrollmentAdvancedEventAndWholeSnapshotRetryAreReadWithoutMutation()
      throws Exception {
    TestContext context = newTestContext();
    initializeCanonicalIssuer(context);
    UUID operation = UUID.randomUUID();
    var baseline = withPeer(() -> context.owner().read(request(operation)));

    assertThat(baseline.evidence().issuerAuthGeneration()).isEqualTo("1");
    assertThat(baseline.evidence().sourceVersion()).isEqualTo("1");
    assertThat(baseline.evidence().lastCommittedOutboxSequence()).isEqualTo("0");
    assertThat(baseline.evidence().sourceEvent()).isEmpty();
    assertThat(baseline.evidence().callerWorkload()).isEqualTo(CALLER);
    assertThat(context.sourceHead()).containsEntry("current_generation", 1L);

    var exactInitialRetry =
        withPeer(() -> context.owner().read(request(operation, Optional.of(baseline.evidence()))));
    assertThat(exactInitialRetry.evidence()).isEqualTo(baseline.evidence());

    String mutationRequestId = "historical-issuer-change-" + UUID.randomUUID();
    context
        .transaction()
        .executeWithoutResult(
            status ->
                context
                    .sources()
                    .appendIssuerAuthorityChange(ISSUER, "SIGNER_COMPROMISE", mutationRequestId));

    Map<String, Object> beforeStaleRetry = context.sourceHead();
    var advanced = withPeer(() -> context.owner().read(request(UUID.randomUUID())));
    assertThat(advanced.evidence().issuerAuthGeneration()).isEqualTo("2");
    assertThat(advanced.evidence().sourceVersion()).isEqualTo("2");
    assertThat(advanced.evidence().lastCommittedOutboxSequence()).isEqualTo("1");
    assertThat(advanced.evidence().sourceEvent()).isPresent();
    var event =
        (AccountAuthoritySourceEventV1Codec.IssuerEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                advanced.evidence().sourceEvent().orElseThrow());
    assertThat(event.eventId()).isEqualTo(mutationRequestId);
    assertThat(event.requestId()).isEqualTo(mutationRequestId);
    assertThat(event.eventId())
        .isNotEqualTo(advanced.evidence().reconciliationOperationId().toString());
    assertThat(event.issuerId()).isEqualTo(ISSUER);
    assertThat(event.outboxStreamKey()).isEqualTo(advanced.evidence().outboxStreamKey());
    assertThat(event.outboxSequence()).isEqualTo(advanced.evidence().lastCommittedOutboxSequence());
    assertThat(event.issuerAuthGeneration()).isEqualTo(advanced.evidence().issuerAuthGeneration());
    assertThat(event.sourceVersion()).isEqualTo(advanced.evidence().sourceVersion());

    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () ->
            withPeer(
                () -> context.owner().read(request(operation, Optional.of(baseline.evidence())))));
    assertThat(context.sourceHead()).isEqualTo(beforeStaleRetry);

    var exactAdvancedRetry =
        withPeer(
            () ->
                context
                    .owner()
                    .read(
                        request(
                            advanced.evidence().reconciliationOperationId(),
                            Optional.of(advanced.evidence()))));
    assertThat(exactAdvancedRetry.evidence()).isEqualTo(advanced.evidence());
    assertThat(context.sourceHead()).isEqualTo(beforeStaleRetry);
  }

  @Test
  void missingAndUnsupportedIssuerReadsDenyWithoutEnrollmentOrOutboxMutation() throws Exception {
    TestContext context = newTestContext();
    Map<String, Object> before = context.allIssuerRows();

    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> withPeer(() -> context.owner().read(request(UUID.randomUUID()))));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        () ->
            withPeer(
                () ->
                    context
                        .owner()
                        .read(request(UUID.randomUUID(), "unsupported-issuer", Optional.empty()))));

    assertThat(context.allIssuerRows()).isEqualTo(before);
    assertThat(context.allIssuerRows()).containsEntry("generation_rows", 0L);
    assertThat(context.allIssuerRows()).containsEntry("source_rows", 0L);
    assertThat(context.allIssuerRows()).containsEntry("stream_rows", 0L);
    assertThat(context.allIssuerRows()).containsEntry("event_rows", 0L);
  }

  @Test
  void serializableSnapshotWaitsForIssuerAdvanceAndExactRetryCannotSilentlyRefresh()
      throws Exception {
    TestContext context = newTestContext();
    initializeCanonicalIssuer(context);
    UUID operation = UUID.randomUUID();
    var original = withPeer(() -> context.owner().read(request(operation)));

    CountDownLatch writerLocked = new CountDownLatch(1);
    CountDownLatch writerAppended = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CountDownLatch readerStarted = new CountDownLatch(1);
    var writerPid = new java.util.concurrent.CompletableFuture<Integer>();
    var readerPid = new java.util.concurrent.CompletableFuture<Integer>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    String mutationRequestId = "concurrent-issuer-change-" + UUID.randomUUID();
    Future<?> writer =
        executor.submit(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          var writerBackendPidRow =
                              Objects.requireNonNull(
                                  context.dsl().fetchOne("SELECT pg_backend_pid()"),
                                  "Writer backend PID query returned no row");
                          writerPid.complete(
                              Objects.requireNonNull(
                                  writerBackendPidRow.get(0, Integer.class),
                                  "Writer backend PID query returned NULL"));
                          var locked =
                              context
                                  .dsl()
                                  .fetchOne(
                                      "SELECT generation FROM account_authority_generations "
                                          + "WHERE scope_kind = 'ISSUER' AND issuer_id = ? FOR UPDATE",
                                      ISSUER);
                          assertThat(locked).isNotNull();
                          writerLocked.countDown();
                          context
                              .sources()
                              .appendIssuerAuthorityChange(
                                  ISSUER, "SIGNER_COMPROMISE", mutationRequestId);
                          writerAppended.countDown();
                          await(releaseWriter, "Issuer writer was not released to commit");
                        }));
    Future<AccountIssuerSourceSnapshotReadOwner.ReadResult> reader =
        executor.submit(
            () -> {
              await(writerAppended, "Issuer writer did not append the source event");
              readerStarted.countDown();
              context.sources().captureNextReadBackendPid(readerPid);
              return withPeer(
                  () -> context.owner().read(request(operation, Optional.of(original.evidence()))));
            });

    try {
      assertThat(writerLocked.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(writerAppended.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(readerStarted.await(30, TimeUnit.SECONDS)).isTrue();
      int exactWriterPid = writerPid.get(10, TimeUnit.SECONDS);
      int exactReaderPid = readerPid.get(10, TimeUnit.SECONDS);
      assertThat(exactReaderPid).isNotEqualTo(exactWriterPid);
      awaitBlockedReader(context.dsl(), exactReaderPid, exactWriterPid);
      releaseWriter.countDown();
      writer.get(30, TimeUnit.SECONDS);

      try {
        reader.get(30, TimeUnit.SECONDS);
        throw new AssertionError(
            "The exact old source snapshot must conflict after issuer advance");
      } catch (ExecutionException conflicted) {
        Status.Code code = Status.fromThrowable(conflicted.getCause()).getCode();
        assertThat(code).isIn(Status.Code.FAILED_PRECONDITION, Status.Code.UNAVAILABLE);
      }

      assertStatus(
          Status.Code.FAILED_PRECONDITION,
          () ->
              withPeer(
                  () ->
                      context.owner().read(request(operation, Optional.of(original.evidence())))));

      var current = withPeer(() -> context.owner().read(request(UUID.randomUUID()))).evidence();
      assertThat(current.issuerAuthGeneration()).isEqualTo("2");
      assertThat(current.sourceVersion()).isEqualTo("2");
      assertThat(current.lastCommittedOutboxSequence()).isEqualTo("1");
      var event =
          (AccountAuthoritySourceEventV1Codec.IssuerEvent)
              AccountAuthoritySourceEventV1Codec.verify(current.sourceEvent().orElseThrow());
      assertThat(event.eventId()).isEqualTo(mutationRequestId);
      assertThat(event.requestId()).isEqualTo(mutationRequestId);
      assertThat(event.issuerAuthGeneration()).isEqualTo(current.issuerAuthGeneration());
      assertThat(event.sourceVersion()).isEqualTo(current.sourceVersion());
      assertThat(event.outboxSequence()).isEqualTo(current.lastCommittedOutboxSequence());
      assertThat(context.sourceHead()).containsEntry("current_generation", 2L);
      assertThat(context.sourceHead()).containsEntry("current_source_version", 2L);
      assertThat(context.sourceHead()).containsEntry("last_outbox_sequence", 1L);
    } finally {
      releaseWriter.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static void initializeCanonicalIssuer(TestContext context) {
    context
        .transaction()
        .executeWithoutResult(status -> context.sources().initializeIssuerIfAbsent(ISSUER));
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var transaction = new TransactionTemplate(transactionManager);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sources = new BackendPidCapturingSourceRepository(dsl, generations, outbox);
    var owner = new AccountIssuerSourceSnapshotReadOwner(sources, transactionManager, NAMESPACE);
    return new TestContext(dsl, transaction, sources, owner);
  }

  private static void awaitBlockedReader(DSLContext dsl, int readerPid, int writerPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      var blockedReader =
          dsl.fetchOne(
              "SELECT pid FROM pg_stat_activity "
                  + "WHERE pid = ? AND pid <> pg_backend_pid() "
                  + "AND datname = current_database() AND wait_event_type = 'Lock' "
                  + "AND ? = ANY(pg_blocking_pids(pid)) LIMIT 1",
              readerPid,
              writerPid);
      if (blockedReader != null) return;
      Thread.sleep(25L);
    }
    throw new AssertionError(
        "The owned issuer source reader did not wait for the exact concurrent writer");
  }

  private static void await(CountDownLatch latch, String failureMessage) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException(failureMessage);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failureMessage, interrupted);
    }
  }

  private static ReadCurrentIssuerAuthoritySourceRequest request(UUID operation) {
    return request(operation, ISSUER, Optional.empty());
  }

  private static ReadCurrentIssuerAuthoritySourceRequest request(
      UUID operation, Optional<AccountIssuerSourceSnapshotEvidence> expected) {
    return request(operation, ISSUER, expected);
  }

  private static ReadCurrentIssuerAuthoritySourceRequest request(
      UUID operation, String issuer, Optional<AccountIssuerSourceSnapshotEvidence> expected) {
    if (!ISSUER.equals(issuer)) {
      return ReadCurrentIssuerAuthoritySourceRequest.newBuilder()
          .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
          .setTargetNamespace(NAMESPACE)
          .setReconciliationOperationId(operation.toString())
          .setIssuerId(issuer)
          .build();
    }
    var typed =
        new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
            operation, NAMESPACE, issuer, expected);
    return AccountIssuerSourceSnapshotGrpcCodec.toRequest(typed, NAMESPACE, CALLER);
  }

  private static <T> T withPeer(java.util.concurrent.Callable<T> call) throws Exception {
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(CALLER).orElseThrow();
    return Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).call(call);
  }

  private static void assertStatus(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private record TestContext(
      DSLContext dsl,
      TransactionTemplate transaction,
      BackendPidCapturingSourceRepository sources,
      AccountIssuerSourceSnapshotReadOwner owner) {
    private Map<String, Object> sourceHead() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT g.generation AS authority_generation, "
                      + "g.source_version AS authority_source_version, "
                      + "s.current_generation, s.current_source_version, s.last_outbox_sequence, "
                      + "stream.last_sequence AS stream_last_sequence, "
                      + "(SELECT count(*) FROM account_authority_outbox_events event "
                      + "WHERE event.outbox_stream_key = stream.outbox_stream_key) AS retained_events "
                      + "FROM account_authority_generations g "
                      + "JOIN account_authority_source_records s "
                      + "ON s.outbox_stream_key = 'account:auth-authority:v1:issuer/' || g.issuer_id "
                      + "JOIN account_authority_outbox_streams stream "
                      + "ON stream.outbox_stream_key = s.outbox_stream_key "
                      + "WHERE g.scope_kind = 'ISSUER' AND g.issuer_id = ?",
                  ISSUER),
              "Issuer source head query returned no row")
          .intoMap();
    }

    private Map<String, Object> allIssuerRows() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT "
                      + "(SELECT count(*) FROM account_authority_generations WHERE scope_kind = 'ISSUER') AS generation_rows, "
                      + "(SELECT count(*) FROM account_authority_source_records WHERE scope_kind = 'ISSUER') AS source_rows, "
                      + "(SELECT count(*) FROM account_authority_outbox_streams WHERE outbox_stream_key LIKE 'account:auth-authority:v1:issuer/%') AS stream_rows, "
                      + "(SELECT count(*) FROM account_authority_outbox_events WHERE outbox_stream_key LIKE 'account:auth-authority:v1:issuer/%') AS event_rows"),
              "Issuer source row-count query returned no row")
          .intoMap();
    }
  }

  /** Captures only the next owner's real PostgreSQL backend before delegating the actual read. */
  private static final class BackendPidCapturingSourceRepository
      extends AccountAuthoritySourceEvidenceRepository {
    private final DSLContext dsl;
    private final AtomicReference<java.util.concurrent.CompletableFuture<Integer>> nextReadPid =
        new AtomicReference<>();

    private BackendPidCapturingSourceRepository(
        DSLContext dsl,
        AccountAuthorityGenerationRepository generations,
        AccountAuthorityOutboxRepository outbox) {
      super(dsl, generations, outbox);
      this.dsl = dsl;
    }

    private void captureNextReadBackendPid(
        java.util.concurrent.CompletableFuture<Integer> capturedPid) {
      if (!nextReadPid.compareAndSet(null, capturedPid)) {
        throw new IllegalStateException("A PostgreSQL owner-read PID capture is already armed");
      }
    }

    @Override
    public CanonicalIssuerSourceSnapshot readCurrentCanonicalIssuerSource(String exactIssuerId) {
      var capture = nextReadPid.getAndSet(null);
      if (capture != null) {
        try {
          if (!TransactionSynchronizationManager.isActualTransactionActive()
              || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
              || !Integer.valueOf(TransactionDefinition.ISOLATION_SERIALIZABLE)
                  .equals(
                      TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException(
                "The captured Account owner read must use its writable SERIALIZABLE transaction");
          }
          var readerBackendPidRow =
              Objects.requireNonNull(
                  dsl.fetchOne("SELECT pg_backend_pid()"),
                  "Reader backend PID query returned no row");
          int backendPid =
              Objects.requireNonNull(
                  readerBackendPidRow.get(0, Integer.class),
                  "Reader backend PID query returned NULL");
          capture.complete(backendPid);
        } catch (RuntimeException failure) {
          capture.completeExceptionally(failure);
          throw failure;
        }
      }
      return super.readCurrentCanonicalIssuerSource(exactIssuerId);
    }
  }
}
