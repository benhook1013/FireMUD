package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionReconciliationService;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IssuerProjectionReconciliationPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "issuer_reconcile_pg";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String OTHER_ISSUER_ID = "https://other.example.test/issuer";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final String GAME_SESSION_ID = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_GAME_SESSION_ID =
      "spiffe://firemud/ns/other/sa/game-session-service";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void capturesProvedZeroBaselineWithoutSourceOrOutboxWrites() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    StoredState before = snapshot(fixture);
    UUID requestId = UUID.randomUUID();

    Receipt receipt = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, requestId);

    assertThat(receipt.operationId()).isNotEqualTo(NIL_UUID).isNotEqualTo(requestId);
    assertThat(receipt.requestId()).isEqualTo(requestId);
    assertThat(receipt.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(receipt.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_ID);
    assertThat(receipt.projectionKey()).isEqualTo(PROJECTION_KEY);
    assertThat(receipt.requestDigestVersion()).isEqualTo(1);
    assertThat(receipt.requestDigest())
        .isEqualTo(expectedRequestDigest(ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, requestId));
    assertZeroSnapshot(receipt.capturedSource());
    assertThat(snapshot(fixture)).isEqualTo(before.withReceiptCount(1L));
    assertStoredReceipt(fixture, receipt);
  }

  @Test
  void capturesCompletePositiveCheckpointWithIndependentSourceCounters() {
    Fixture fixture = newFixture();
    IssuerGenerationAuthorityEvent sourceEvent = seedNonSequenceAlignedPositiveState(fixture);
    UUID requestId = UUID.randomUUID();

    Receipt receipt = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, requestId);

    IssuerAuthoritySnapshot captured = receipt.capturedSource();
    assertThat(captured.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(captured.issuerAuthGeneration()).isEqualTo(4L);
    assertThat(captured.sourceVersion()).isEqualTo(8L);
    assertThat(captured.outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(captured.outboxSequence()).isEqualTo(1L);
    assertThat(captured.issuerAuthGeneration()).isNotEqualTo(captured.outboxSequence() + 1L);
    assertThat(captured.latestEvent()).isPresent();
    assertSameEvent(sourceEvent, captured.latestEvent().orElseThrow());
    assertThat(receipt.operationId()).isNotEqualTo(NIL_UUID).isNotEqualTo(requestId);
    assertThat(receipt.requestDigest())
        .isEqualTo(expectedRequestDigest(ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, requestId));
    assertStoredReceipt(fixture, receipt);
  }

  @Test
  void databaseRejectsIncompleteAndContradictoryPositiveReceiptEvidenceWithoutMutation() {
    Fixture fixture = newFixture();
    IssuerGenerationAuthorityEvent sourceEvent = seedNonSequenceAlignedPositiveState(fixture);
    Receipt original = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    StoredState committed = snapshot(fixture);
    IssuerAuthoritySnapshot source = original.capturedSource();

    // Each raw setup DSL statement runs independently in autocommit mode. A rejected insert
    // therefore cannot leave the PostgreSQL transaction aborted for the next guard case.
    UUID missingDigestRequest = UUID.randomUUID();
    assertReceiptInsertRejected(
        fixture,
        original,
        UUID.randomUUID(),
        missingDigestRequest,
        source.outboxSequence(),
        sourceEvent.eventId(),
        null,
        sourceEvent.canonicalJsonUtf8(),
        "Issuer reconciliation receipt differs from its source event",
        committed);

    UUID missingEventSequenceRequest = UUID.randomUUID();
    assertReceiptInsertRejected(
        fixture,
        original,
        UUID.randomUUID(),
        missingEventSequenceRequest,
        null,
        sourceEvent.eventId(),
        sourceEvent.eventDigest(),
        sourceEvent.canonicalJsonUtf8(),
        "account_issuer_projection_reconciliation_checkpoint_check",
        committed);

    IssuerGenerationAuthorityEvent contradictoryEvent = contradictoryCanonicalEvent(sourceEvent);
    assertReceiptInsertRejected(
        fixture,
        original,
        UUID.randomUUID(),
        UUID.randomUUID(),
        source.outboxSequence(),
        contradictoryEvent.eventId(),
        contradictoryEvent.eventDigest(),
        contradictoryEvent.canonicalJsonUtf8(),
        "Issuer reconciliation receipt differs from its source event",
        committed);

    assertThat(snapshot(fixture)).isEqualTo(committed);
    assertThat(countReceipts(fixture)).isEqualTo(1L);
    assertThat(countEvents(fixture)).isEqualTo(1L);
    assertStoredReceipt(fixture, original);
  }

  @Test
  void committedCaptureWithLostPostCommitReadbackRecoversTheSameReceipt() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    AtomicInteger receiptReads = new AtomicInteger();
    AccountIssuerProjectionReconciliationRepository hideOnePostCommitRead =
        new AccountIssuerProjectionReconciliationRepository(fixture.transactionDsl()) {
          @Override
          public Optional<Receipt> findByIssuerAndRequestId(String issuerId, UUID requestId) {
            if (receiptReads.incrementAndGet() == 3) {
              return Optional.empty();
            }
            return super.findByIssuerAndRequestId(issuerId, requestId);
          }
        };
    AccountIssuerProjectionReconciliationService service =
        fixture.service(hideOnePostCommitRead, GAME_SESSION_ID);
    UUID requestId = UUID.randomUUID();
    StoredState before = snapshot(fixture);

    assertThatThrownBy(() -> service.capture(ISSUER_ID, GAME_SESSION_ID, requestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Post-commit issuer reconciliation receipt readback is missing");

    StoredState committed = snapshot(fixture);
    assertThat(committed).isEqualTo(before.withReceiptCount(1L));
    Receipt recovered = service.capture(ISSUER_ID, GAME_SESSION_ID, requestId);

    assertThat(recovered.operationId()).isNotEqualTo(NIL_UUID).isNotEqualTo(requestId);
    assertThat(recovered.capturedSource().outboxSequence()).isZero();
    assertStoredReceipt(fixture, recovered);
    assertThat(snapshot(fixture)).isEqualTo(committed);
  }

  @Test
  void exactRetryReturnsOriginalPositiveReceiptAfterLaterSourceAdvance() {
    Fixture fixture = newFixture();
    IssuerGenerationAuthorityEvent firstSourceEvent = seedNonSequenceAlignedPositiveState(fixture);
    UUID requestId = UUID.randomUUID();
    Receipt original = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, requestId);
    IssuerGenerationAuthorityEvent laterSourceEvent =
        fixture.source().advance(ISSUER_ID, UUID.randomUUID(), 4L, 8L);

    Receipt retry = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, requestId);

    assertSameReceipt(original, retry);
    assertThat(original.capturedSource().outboxSequence()).isEqualTo(1L);
    assertThat(original.capturedSource().issuerAuthGeneration()).isEqualTo(4L);
    assertThat(original.capturedSource().sourceVersion()).isEqualTo(8L);
    assertSameEvent(firstSourceEvent, original.capturedSource().latestEvent().orElseThrow());
    assertThat(laterSourceEvent.outboxSequence()).isEqualTo("2");
    IssuerAuthoritySnapshot current = fixture.source().readCurrent(ISSUER_ID);
    assertThat(current.outboxSequence()).isEqualTo(2L);
    assertThat(current.issuerAuthGeneration()).isEqualTo(5L);
    assertThat(current.sourceVersion()).isEqualTo(9L);
    assertSameEvent(laterSourceEvent, current.latestEvent().orElseThrow());
    assertThat(countReceipts(fixture)).isEqualTo(1L);
    assertStoredReceipt(fixture, original);
  }

  @Test
  void concurrentExactCapturesReturnOneDurableOperation() throws Exception {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    UUID requestId = UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Receipt> first =
          executor.submit(() -> concurrentCapture(fixture, requestId, ready, start));
      Future<Receipt> second =
          executor.submit(() -> concurrentCapture(fixture, requestId, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      Receipt firstResult = first.get(45, TimeUnit.SECONDS);
      Receipt secondResult = second.get(45, TimeUnit.SECONDS);

      assertSameReceipt(firstResult, secondResult);
      assertThat(firstResult.operationId()).isNotEqualTo(NIL_UUID).isNotEqualTo(requestId);
      assertStoredReceipt(fixture, firstResult);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertThat(countReceipts(fixture)).isEqualTo(1L);
    assertThat(countEvents(fixture)).isZero();
    assertThat(countStreams(fixture)).isZero();
  }

  @Test
  void captureAndSourceAdvanceSerializeOnOneIssuerFenceWithoutHybridSnapshot() throws Exception {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    CountDownLatch insertEntered = new CountDownLatch(1);
    CountDownLatch releaseInsert = new CountDownLatch(1);
    AtomicBoolean blockFirstInsert = new AtomicBoolean(true);
    AccountIssuerProjectionReconciliationRepository blockingRepository =
        new AccountIssuerProjectionReconciliationRepository(fixture.transactionDsl()) {
          @Override
          public Receipt insert(Receipt receipt) {
            if (blockFirstInsert.compareAndSet(true, false)) {
              insertEntered.countDown();
              await(releaseInsert);
            }
            return super.insert(receipt);
          }
        };
    AccountIssuerProjectionReconciliationService service =
        fixture.service(blockingRepository, GAME_SESSION_ID);
    UUID requestId = UUID.randomUUID();
    UUID advanceRequestId = UUID.randomUUID();
    CountDownLatch advanceStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Receipt> capture =
          executor.submit(() -> service.capture(ISSUER_ID, GAME_SESSION_ID, requestId));
      assertThat(insertEntered.await(20, TimeUnit.SECONDS)).isTrue();
      Future<IssuerGenerationAuthorityEvent> advance =
          executor.submit(
              () -> {
                advanceStarted.countDown();
                return fixture.source().advance(ISSUER_ID, advanceRequestId, 1L, 1L);
              });
      assertThat(advanceStarted.await(20, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> advance.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseInsert.countDown();
      Receipt captured = capture.get(45, TimeUnit.SECONDS);
      IssuerGenerationAuthorityEvent advanced = advance.get(45, TimeUnit.SECONDS);

      assertZeroSnapshot(captured.capturedSource());
      assertThat(advanced.outboxSequence()).isEqualTo("1");
      assertThat(advanced.issuerAuthGeneration()).isEqualTo("2");
      assertThat(advanced.sourceVersion()).isEqualTo("2");
      IssuerAuthoritySnapshot current = fixture.source().readCurrent(ISSUER_ID);
      assertThat(current.outboxSequence()).isEqualTo(1L);
      assertThat(current.issuerAuthGeneration()).isEqualTo(2L);
      assertThat(current.sourceVersion()).isEqualTo(2L);
      assertSameEvent(advanced, current.latestEvent().orElseThrow());
      assertThat(captured.capturedSource().outboxSequence()).isZero();
      assertThat(countReceipts(fixture)).isEqualTo(1L);
      assertStoredReceipt(fixture, captured);
      Receipt zeroReceiptRetry = service.capture(ISSUER_ID, GAME_SESSION_ID, requestId);
      assertSameReceipt(captured, zeroReceiptRetry);
      assertThat(zeroReceiptRetry.capturedSource().outboxSequence()).isZero();
    } finally {
      releaseInsert.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void invalidBindingsAndMissingAuthorityDenyWithoutEnrollment() {
    // These local string comparisons do not prove certificate authentication of a transport peer.
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    StoredState before = snapshot(fixture);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() -> fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, NIL_UUID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.service().capture(OTHER_ISSUER_ID, GAME_SESSION_ID, requestId))
        .isInstanceOf(AccountIssuerAuthorityEventProducer.IssuerMismatchException.class);
    assertThatThrownBy(() -> fixture.service().capture(ISSUER_ID, OTHER_GAME_SESSION_ID, requestId))
        .isInstanceOf(SecurityException.class);
    assertThat(snapshot(fixture)).isEqualTo(before);

    Receipt original = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, requestId);
    AccountIssuerProjectionReconciliationService changedCallerService =
        fixture.service(fixture.receipts(), OTHER_GAME_SESSION_ID);
    assertThatThrownBy(
            () -> changedCallerService.capture(ISSUER_ID, OTHER_GAME_SESSION_ID, requestId))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(countReceipts(fixture)).isEqualTo(1L);
    assertStoredReceipt(fixture, original);

    Fixture missingAuthority = newFixture();
    assertThatThrownBy(
            () -> missingAuthority.service().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(missingAuthority).generation()).isNull();
    assertThat(snapshot(missingAuthority).sourceVersion()).isNull();
    assertThat(countIssuerRows(missingAuthority)).isZero();
    assertThat(countEvents(missingAuthority)).isZero();
    assertThat(countStreams(missingAuthority)).isZero();
    assertThat(countReceipts(missingAuthority)).isZero();
  }

  @Test
  void durableReceiptsRejectUpdateAndDelete() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    Receipt receipt = fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    StoredState committed = snapshot(fixture);

    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_issuer_projection_reconciliation_receipts "
                            + "SET caller_workload_identity = ? WHERE operation_id = ?",
                        OTHER_GAME_SESSION_ID,
                        receipt.operationId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("Issuer projection reconciliation receipts are immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_issuer_projection_reconciliation_receipts "
                            + "WHERE operation_id = ?",
                        receipt.operationId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("Issuer projection reconciliation receipts are immutable");

    assertThat(snapshot(fixture)).isEqualTo(committed);
    assertStoredReceipt(fixture, receipt);
  }

  private Fixture newFixture() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    AccountIssuerProjectionReconciliationRepository receipts =
        new AccountIssuerProjectionReconciliationRepository(transactionDsl);
    AccountIssuerAuthorityEventProducer source =
        new AccountIssuerAuthorityEventProducer(
            ISSUER_ID, generations, outbox, transactionDsl, transactionManager);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        generations,
        outbox,
        receipts,
        source);
  }

  private void seedIssuer(Fixture fixture) {
    transaction(
        fixture.transaction(),
        () -> {
          fixture.generations().initializeIssuerIfAbsent(ISSUER_ID);
          return null;
        });
  }

  private IssuerGenerationAuthorityEvent seedNonSequenceAlignedPositiveState(Fixture fixture) {
    fixture
        .setupDsl()
        .execute(
            "INSERT INTO account_authority_generations "
                + "(scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                + "VALUES ('ISSUER', ?, NULL, NULL, 3, 7)",
            ISSUER_ID);
    UUID sourceRequestId = UUID.randomUUID();
    transaction(
        fixture.transaction(),
        () -> {
          ScopeState current = fixture.generations().read(AuthorityScope.issuer(ISSUER_ID));
          ScopeState advanced = fixture.generations().advance(current, null);
          assertThat(advanced.generation()).isEqualTo(4L);
          assertThat(advanced.sourceVersion()).isEqualTo(8L);
          appendSeedEvent(fixture, sourceRequestId, 4L, 8L);
          return null;
        });
    return fixture.source().readCurrent(ISSUER_ID).latestEvent().orElseThrow();
  }

  private void appendSeedEvent(
      Fixture fixture, UUID requestId, long generation, long sourceVersion) {
    String requestText = requestId.toString();
    String eventId = "account-issuer-authority-event-v1:" + requestText;
    fixture
        .outbox()
        .append(
            STREAM_KEY,
            requestText,
            sequence -> {
              IssuerGenerationAuthorityEvent event =
                  IssuerGenerationAuthorityEventV1Codec.seal(
                      Map.of(
                          "schemaVersion",
                          IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                          "eventType",
                          IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
                          "eventId",
                          eventId,
                          "requestId",
                          requestText,
                          "issuerId",
                          ISSUER_ID,
                          "sourceScope",
                          "issuer/" + ISSUER_ID,
                          "outboxStreamKey",
                          STREAM_KEY,
                          "outboxSequence",
                          Long.toString(sequence),
                          "issuerAuthGeneration",
                          Long.toString(generation),
                          "sourceVersion",
                          Long.toString(sourceVersion)));
              return new AccountAuthorityOutboxRepository.EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
  }

  private IssuerGenerationAuthorityEvent contradictoryCanonicalEvent(
      IssuerGenerationAuthorityEvent sourceEvent) {
    String requestId = UUID.randomUUID().toString();
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-issuer-authority-event-v1:" + requestId,
            "requestId",
            requestId,
            "issuerId",
            sourceEvent.issuerId(),
            "sourceScope",
            sourceEvent.sourceScope(),
            "outboxStreamKey",
            sourceEvent.outboxStreamKey(),
            "outboxSequence",
            sourceEvent.outboxSequence(),
            "issuerAuthGeneration",
            sourceEvent.issuerAuthGeneration(),
            "sourceVersion",
            sourceEvent.sourceVersion()));
  }

  private void assertReceiptInsertRejected(
      Fixture fixture,
      Receipt binding,
      UUID operationId,
      UUID requestId,
      Long eventSequence,
      String eventId,
      String eventDigest,
      byte[] eventPayload,
      String expectedSqlGuard,
      StoredState expectedState) {
    IssuerAuthoritySnapshot source = binding.capturedSource();
    byte[] requestDigest =
        HexFormat.of()
            .parseHex(
                expectedRequestDigest(
                    binding.issuerId(),
                    binding.callerWorkloadIdentity(),
                    binding.projectionKey(),
                    requestId));
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "INSERT INTO account_issuer_projection_reconciliation_receipts "
                            + "(operation_id, request_id, issuer_id, caller_workload_identity, "
                            + "projection_key, request_digest_version, request_digest, "
                            + "issuer_auth_generation, source_version, outbox_stream_key, "
                            + "outbox_sequence, event_outbox_sequence, event_id, event_digest, event_payload) "
                            + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, CAST(? AS BIGINT), ?, ?, ?)",
                        operationId,
                        requestId,
                        binding.issuerId(),
                        binding.callerWorkloadIdentity(),
                        binding.projectionKey(),
                        requestDigest,
                        source.issuerAuthGeneration(),
                        source.sourceVersion(),
                        source.outboxStreamKey(),
                        source.outboxSequence(),
                        eventSequence,
                        eventId,
                        eventDigest,
                        eventPayload))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining(expectedSqlGuard);
    assertThat(snapshot(fixture)).isEqualTo(expectedState);
  }

  private Receipt concurrentCapture(
      Fixture fixture, UUID requestId, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return fixture.service().capture(ISSUER_ID, GAME_SESSION_ID, requestId);
  }

  private void assertZeroSnapshot(IssuerAuthoritySnapshot snapshot) {
    assertThat(snapshot.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(snapshot.issuerAuthGeneration()).isEqualTo(1L);
    assertThat(snapshot.sourceVersion()).isEqualTo(1L);
    assertThat(snapshot.outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(snapshot.outboxSequence()).isZero();
    assertThat(snapshot.latestEvent()).isEmpty();
  }

  private void assertStoredReceipt(Fixture fixture, Receipt expected) {
    Record stored =
        fixture
            .setupDsl()
            .fetchOne(
                "SELECT operation_id, request_id, issuer_id, caller_workload_identity, "
                    + "projection_key, request_digest_version, request_digest, "
                    + "issuer_auth_generation, source_version, outbox_stream_key, "
                    + "outbox_sequence, event_outbox_sequence, event_id, event_digest, event_payload "
                    + "FROM account_issuer_projection_reconciliation_receipts "
                    + "WHERE issuer_id = ? AND request_id = ?",
                expected.issuerId(),
                expected.requestId());
    assertThat(stored).isNotNull();
    assertThat(stored.get("operation_id", UUID.class)).isEqualTo(expected.operationId());
    assertThat(stored.get("request_id", UUID.class)).isEqualTo(expected.requestId());
    assertThat(stored.get("issuer_id", String.class)).isEqualTo(expected.issuerId());
    assertThat(stored.get("caller_workload_identity", String.class))
        .isEqualTo(expected.callerWorkloadIdentity());
    assertThat(stored.get("projection_key", String.class)).isEqualTo(expected.projectionKey());
    assertThat(stored.get("request_digest_version", Integer.class))
        .isEqualTo(expected.requestDigestVersion());
    assertThat(HexFormat.of().formatHex(stored.get("request_digest", byte[].class)))
        .isEqualTo(expected.requestDigest());

    IssuerAuthoritySnapshot source = expected.capturedSource();
    assertThat(stored.get("issuer_auth_generation", Long.class))
        .isEqualTo(source.issuerAuthGeneration());
    assertThat(stored.get("source_version", Long.class)).isEqualTo(source.sourceVersion());
    assertThat(stored.get("outbox_stream_key", String.class)).isEqualTo(source.outboxStreamKey());
    assertThat(stored.get("outbox_sequence", Long.class)).isEqualTo(source.outboxSequence());
    if (source.outboxSequence() == 0L) {
      assertThat(stored.get("event_outbox_sequence", Long.class)).isNull();
      assertThat(stored.get("event_id", String.class)).isNull();
      assertThat(stored.get("event_digest", String.class)).isNull();
      assertThat(stored.get("event_payload", byte[].class)).isNull();
      assertThat(source.latestEvent()).isEmpty();
    } else {
      IssuerGenerationAuthorityEvent event = source.latestEvent().orElseThrow();
      assertThat(stored.get("event_outbox_sequence", Long.class))
          .isEqualTo(source.outboxSequence());
      assertThat(stored.get("event_id", String.class)).isEqualTo(event.eventId());
      assertThat(stored.get("event_digest", String.class)).isEqualTo(event.eventDigest());
      assertThat(stored.get("event_payload", byte[].class))
          .containsExactly(event.canonicalJsonUtf8());
      assertThat(event.issuerId()).isEqualTo(ISSUER_ID);
      assertThat(event.sourceScope()).isEqualTo("issuer/" + ISSUER_ID);
      assertThat(event.outboxStreamKey()).isEqualTo(STREAM_KEY);
      assertThat(event.eventDigest()).matches("sha256:[0-9a-f]{64}");
    }
  }

  private void assertSameReceipt(Receipt expected, Receipt actual) {
    assertThat(actual.operationId()).isEqualTo(expected.operationId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.callerWorkloadIdentity()).isEqualTo(expected.callerWorkloadIdentity());
    assertThat(actual.projectionKey()).isEqualTo(expected.projectionKey());
    assertThat(actual.requestDigestVersion()).isEqualTo(expected.requestDigestVersion());
    assertThat(actual.requestDigest()).isEqualTo(expected.requestDigest());
    assertSameSnapshot(expected.capturedSource(), actual.capturedSource());
  }

  private void assertSameSnapshot(
      IssuerAuthoritySnapshot expected, IssuerAuthoritySnapshot actual) {
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.issuerAuthGeneration()).isEqualTo(expected.issuerAuthGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.latestEvent().isPresent()).isEqualTo(expected.latestEvent().isPresent());
    if (expected.latestEvent().isPresent()) {
      assertSameEvent(expected.latestEvent().orElseThrow(), actual.latestEvent().orElseThrow());
    }
  }

  private void assertSameEvent(
      IssuerGenerationAuthorityEvent expected, IssuerGenerationAuthorityEvent actual) {
    assertThat(actual.schemaVersion()).isEqualTo(expected.schemaVersion());
    assertThat(actual.eventType()).isEqualTo(IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(actual.eventType()).isEqualTo(expected.eventType());
    assertThat(actual.eventId()).isEqualTo(expected.eventId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.sourceScope()).isEqualTo(expected.sourceScope());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.issuerAuthGeneration()).isEqualTo(expected.issuerAuthGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
    assertThat(actual.canonicalJsonUtf8()).containsExactly(expected.canonicalJsonUtf8());
  }

  private StoredState snapshot(Fixture fixture) {
    Record authority =
        fixture
            .setupDsl()
            .fetchOne(
                "SELECT generation, source_version FROM account_authority_generations "
                    + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
                ISSUER_ID);
    return new StoredState(
        authority == null ? null : authority.get("generation", Long.class),
        authority == null ? null : authority.get("source_version", Long.class),
        countEvents(fixture),
        countStreams(fixture),
        countReceipts(fixture));
  }

  private long countIssuerRows(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_generations "
                    + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
                ISSUER_ID)
            .fetchOne(0, Long.class));
  }

  private long countEvents(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                STREAM_KEY)
            .fetchOne(0, Long.class));
  }

  private long countStreams(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_streams WHERE outbox_stream_key = ?",
                STREAM_KEY)
            .fetchOne(0, Long.class));
  }

  private long countReceipts(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery("SELECT COUNT(*) FROM account_issuer_projection_reconciliation_receipts")
            .fetchOne(0, Long.class));
  }

  private String expectedRequestDigest(
      String issuerId, String callerIdentity, String projectionKey, UUID requestId) {
    String[] fields = {
      "issuer-projection-reconciliation-request/v1",
      "ISSUER_PROJECTION_SNAPSHOT_CAPTURE",
      issuerId,
      callerIdentity,
      projectionKey,
      requestId.toString()
    };
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
      framed.write(':');
      framed.writeBytes(bytes);
    }
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Issuer reconciliation PostgreSQL barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Issuer reconciliation PostgreSQL proof was interrupted", interrupted);
    }
  }

  private <T> T transaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Fixture(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      AccountIssuerProjectionReconciliationRepository receipts,
      AccountIssuerAuthorityEventProducer source) {
    private AccountIssuerProjectionReconciliationService service() {
      return service(receipts, GAME_SESSION_ID);
    }

    private AccountIssuerProjectionReconciliationService service(
        AccountIssuerProjectionReconciliationRepository repository, String callerIdentity) {
      return new AccountIssuerProjectionReconciliationService(
          ISSUER_ID, callerIdentity, source, repository, transactionManager);
    }
  }

  private record StoredState(
      Long generation, Long sourceVersion, long eventCount, long streamCount, long receiptCount) {
    private StoredState withReceiptCount(long newReceiptCount) {
      return new StoredState(generation, sourceVersion, eventCount, streamCount, newReceiptCount);
    }
  }
}
