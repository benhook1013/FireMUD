package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository.Acknowledgment;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountIssuerProjectionAcknowledgmentServiceTest {
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String GAME_SESSION_ID =
      "spiffe://firemud/ns/account-unit/sa/game-session-service";
  private static final String OTHER_GAME_SESSION_ID =
      "spiffe://firemud/ns/other-unit/sa/game-session-service";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final UUID REQUEST_ID = UUID.fromString("a3a69671-8149-4d97-86f8-25a2582fdd68");
  private static final UUID OPERATION_ID = UUID.fromString("91b0eb2d-f7b1-4d88-8db4-2b81cacfc96a");
  private static final UUID OTHER_OPERATION_ID =
      UUID.fromString("a3d2fe11-23ac-40b7-8e85-26dd9e9b9a23");
  private static final String APPLIED_AT = "2026-10-03T00:00:00Z";

  @Test
  void persistsOnlyAfterOwnerTransactionReadbackAndSeparateFencedDurableReadback() {
    Fixture fixture = fixture(baseline(), capture(OPERATION_ID, REQUEST_ID, baseline()));
    AtomicReference<Acknowledgment> stored = fixture.store();

    Acknowledgment result = fixture.acknowledge(baseline(), APPLIED_AT);

    assertThat(result.acknowledgmentId()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(result.captureOperationId()).isEqualTo(OPERATION_ID);
    assertThat(result.captureRequestId()).isEqualTo(REQUEST_ID);
    assertThat(result.installedProjectionUtf8())
        .containsExactly(projectionJson(baseline(), APPLIED_AT).getBytes(StandardCharsets.UTF_8));
    assertThat(stored.get().acknowledgmentId()).isEqualTo(result.acknowledgmentId());
    verify(fixture.transactions(), times(2)).getTransaction(any(TransactionDefinition.class));
    verify(fixture.transactions(), times(2)).commit(any());
    verify(fixture.repository(), times(1)).insert(any(Acknowledgment.class));
    verify(fixture.repository(), times(3)).findByCaptureOperationId(OPERATION_ID);
    verify(fixture.source(), times(2)).readCurrentForProjectionReconciliation(ISSUER_ID);
    verify(fixture.source(), never()).advance(any(), any(), anyLong(), anyLong());
  }

  @Test
  void exactRetryReturnsOriginalAcknowledgmentAfterSourceAdvance() {
    IssuerGenerationAuthorityEvent capturedEvent = event(REQUEST_ID, 1L, 2L, 2L);
    IssuerAuthoritySnapshot captured = snapshot(2L, 2L, 1L, capturedEvent);
    IssuerGenerationAuthorityEvent laterEvent = event(UUID.randomUUID(), 2L, 3L, 3L);
    IssuerAuthoritySnapshot later = snapshot(3L, 3L, 2L, laterEvent);
    Fixture fixture = fixture(captured, capture(OPERATION_ID, REQUEST_ID, captured));
    AtomicReference<Acknowledgment> stored = fixture.store();

    Acknowledgment original = fixture.acknowledge(captured, APPLIED_AT);
    when(fixture.source().readCurrentForProjectionReconciliation(ISSUER_ID))
        .thenReturn(later, later);
    when(fixture.source().readCommittedEventForProjectionReconciliation(ISSUER_ID, 1L))
        .thenReturn(
            new IssuerAuthorityEventReadback(later, capturedEvent),
            new IssuerAuthorityEventReadback(later, capturedEvent));

    Acknowledgment recovered = fixture.acknowledge(captured, APPLIED_AT);

    assertThat(recovered.acknowledgmentId()).isEqualTo(original.acknowledgmentId());
    assertThat(recovered.installedProjectionUtf8()).isEqualTo(original.installedProjectionUtf8());
    assertThat(stored.get().acknowledgmentId()).isEqualTo(original.acknowledgmentId());
    verify(fixture.repository(), times(1)).insert(any(Acknowledgment.class));
    verify(fixture.source(), times(2)).readCommittedEventForProjectionReconciliation(ISSUER_ID, 1L);
  }

  @Test
  void concurrentExactRetriesSerializeOnOwnerTransactionAndReuseOneAcknowledgment()
      throws Exception {
    // This transaction-manager lock models the Account issuer fence. Real PostgreSQL concurrency
    // proof remains a separate integration obligation.
    IssuerAuthoritySnapshot captured = baseline();
    Fixture fixture = fixture(captured, capture(OPERATION_ID, REQUEST_ID, captured));
    ExecutorService attempts = Executors.newFixedThreadPool(2);
    try {
      var first = attempts.submit(() -> fixture.acknowledge(captured, APPLIED_AT));
      var second = attempts.submit(() -> fixture.acknowledge(captured, APPLIED_AT));

      Acknowledgment firstResult = first.get(30, TimeUnit.SECONDS);
      Acknowledgment secondResult = second.get(30, TimeUnit.SECONDS);

      assertThat(firstResult.acknowledgmentId()).isEqualTo(secondResult.acknowledgmentId());
      assertThat(fixture.store().get().acknowledgmentId())
          .isEqualTo(firstResult.acknowledgmentId());
      verify(fixture.repository(), times(1)).insert(any(Acknowledgment.class));
    } finally {
      attempts.shutdownNow();
    }
  }

  @Test
  void changedProjectionBytesConflictWithoutReplacingCommittedAcknowledgment() {
    IssuerAuthoritySnapshot captured = baseline();
    Fixture fixture = fixture(captured, capture(OPERATION_ID, REQUEST_ID, captured));
    fixture.acknowledge(captured, APPLIED_AT);

    assertThatThrownBy(() -> fixture.acknowledge(captured, APPLIED_AT + " "))
        .isInstanceOf(
            AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException.class)
        .hasMessageContaining("changed projection bytes");

    verify(fixture.repository(), times(1)).insert(any(Acknowledgment.class));
  }

  @Test
  void changedCaptureOperationAndDigestConflictBeforeInsert() {
    IssuerAuthoritySnapshot captured = baseline();
    Fixture fixture = fixture(captured, capture(OPERATION_ID, REQUEST_ID, captured));

    assertThatThrownBy(
            () ->
                fixture
                    .service()
                    .acknowledge(
                        ISSUER_ID,
                        GAME_SESSION_ID,
                        OTHER_OPERATION_ID,
                        REQUEST_ID,
                        1,
                        Receipt.requestDigestFor(
                            ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, REQUEST_ID),
                        projectionJson(captured, APPLIED_AT)))
        .isInstanceOf(
            AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException.class)
        .hasMessageContaining("original capture binding");

    verify(fixture.repository(), never()).insert(any(Acknowledgment.class));

    Fixture changedDigest = fixture(captured, capture(OPERATION_ID, REQUEST_ID, captured));
    assertThatThrownBy(
            () ->
                changedDigest
                    .service()
                    .acknowledge(
                        ISSUER_ID,
                        GAME_SESSION_ID,
                        OPERATION_ID,
                        REQUEST_ID,
                        1,
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        projectionJson(captured, APPLIED_AT)))
        .isInstanceOf(
            AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException.class)
        .hasMessageContaining("original capture binding");
    verify(changedDigest.repository(), never()).insert(any(Acknowledgment.class));
  }

  @Test
  void rejectsWrongCallerAndMissingCaptureBeforeAcknowledgmentInsert() {
    Fixture wrongCaller = fixture(baseline(), capture(OPERATION_ID, REQUEST_ID, baseline()));
    assertThatThrownBy(
            () ->
                wrongCaller
                    .service()
                    .acknowledge(
                        ISSUER_ID,
                        OTHER_GAME_SESSION_ID,
                        OPERATION_ID,
                        REQUEST_ID,
                        1,
                        captureDigest(),
                        projectionJson(baseline(), APPLIED_AT)))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("exact authenticated Game Session");
    verifyNoInteractions(
        wrongCaller.source(), wrongCaller.captureRepository(), wrongCaller.repository());

    Fixture missingCapture = fixture(baseline(), null);
    when(missingCapture.captureRepository().findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> missingCapture.acknowledge(baseline(), APPLIED_AT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing capture");
    verify(missingCapture.repository(), never()).insert(any(Acknowledgment.class));
  }

  @Test
  void staleFirstAcknowledgmentAndCompleteProjectionMismatchFailBeforeInsert() {
    IssuerAuthoritySnapshot captured = baseline();
    IssuerGenerationAuthorityEvent advancedEvent = event(UUID.randomUUID(), 1L, 2L, 2L);
    IssuerAuthoritySnapshot advanced = snapshot(2L, 2L, 1L, advancedEvent);
    Fixture stale = fixture(advanced, capture(OPERATION_ID, REQUEST_ID, captured));
    assertThatThrownBy(() -> stale.acknowledge(captured, APPLIED_AT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires the captured source to remain current");
    verify(stale.repository(), never()).insert(any(Acknowledgment.class));

    IssuerGenerationAuthorityEvent capturedEvent = event(REQUEST_ID, 1L, 2L, 2L);
    IssuerAuthoritySnapshot positive = snapshot(2L, 2L, 1L, capturedEvent);
    IssuerGenerationAuthorityEvent otherEvent = event(UUID.randomUUID(), 2L, 3L, 3L);
    IssuerAuthoritySnapshot differentCheckpoint = snapshot(3L, 3L, 2L, otherEvent);
    Fixture mismatch = fixture(positive, capture(OPERATION_ID, REQUEST_ID, positive));
    assertThatThrownBy(() -> mismatch.acknowledge(differentCheckpoint, APPLIED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not exactly match the captured checkpoint");
    verify(mismatch.repository(), never()).insert(any(Acknowledgment.class));
  }

  @Test
  void refusesAmbientTransactionBeforeReadingSourceOrAcknowledgment() {
    Fixture fixture = fixture(baseline(), capture(OPERATION_ID, REQUEST_ID, baseline()));
    boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> fixture.acknowledge(baseline(), APPLIED_AT))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      verifyNoInteractions(fixture.source(), fixture.captureRepository(), fixture.repository());
      verifyNoInteractions(fixture.transactions());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(wasActive);
    }
  }

  @Test
  void requestDigestUsesTheExactTenUtf8FramedSegmentsAndProjectionBytes() throws Exception {
    String projection = projectionJson(baseline(), "2026-10-03T00:00:00Z-ä");
    String digest =
        Acknowledgment.requestDigestFor(
            ISSUER_ID,
            "spiffe://firemud/ns/ä/sa/game-session-service",
            PROJECTION_KEY,
            OPERATION_ID,
            REQUEST_ID,
            1,
            captureDigest(),
            projection);

    assertThat(digest)
        .isEqualTo(
            independentDigest(
                "issuer-projection-installation-ack/v1",
                "ISSUER_PROJECTION_INSTALLATION_ACK",
                ISSUER_ID,
                "spiffe://firemud/ns/ä/sa/game-session-service",
                PROJECTION_KEY,
                OPERATION_ID.toString(),
                REQUEST_ID.toString(),
                "1",
                captureDigest(),
                projection));
    assertThat(digest)
        .isNotEqualTo(
            Acknowledgment.requestDigestFor(
                ISSUER_ID,
                "spiffe://firemud/ns/ä/sa/game-session-service",
                PROJECTION_KEY,
                OPERATION_ID,
                REQUEST_ID,
                1,
                captureDigest(),
                projection + " "));
  }

  @Test
  void repositoryRejectsMissingDslBeforeReadOrInsertSqlAccess() {
    AccountIssuerProjectionAcknowledgmentRepository repository =
        new AccountIssuerProjectionAcknowledgmentRepository(null);
    boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.findByCaptureOperationId(OPERATION_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires a DSLContext");
      assertThatThrownBy(() -> repository.insert(validAcknowledgment(baseline())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires a DSLContext");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(wasActive);
    }
  }

  private static Fixture fixture(IssuerAuthoritySnapshot current, Receipt capture) {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository captureRepository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    AccountIssuerProjectionAcknowledgmentRepository repository =
        mock(AccountIssuerProjectionAcknowledgmentRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    when(source.readCurrentForProjectionReconciliation(ISSUER_ID))
        .thenAnswer(invocation -> current);
    when(captureRepository.findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID))
        .thenReturn(Optional.ofNullable(capture));
    AtomicReference<Acknowledgment> stored = new AtomicReference<>();
    when(repository.findByCaptureOperationId(OPERATION_ID))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(repository.insert(any(Acknowledgment.class)))
        .thenAnswer(
            invocation -> {
              Acknowledgment candidate = invocation.getArgument(0);
              stored.set(candidate);
              return candidate;
            });
    AccountIssuerProjectionReconciliationService captureService =
        new AccountIssuerProjectionReconciliationService(
            ISSUER_ID, GAME_SESSION_ID, source, captureRepository, transactions);
    AccountIssuerProjectionAcknowledgmentService service =
        new AccountIssuerProjectionAcknowledgmentService(
            ISSUER_ID, GAME_SESSION_ID, captureService, repository, transactions);
    return new Fixture(service, source, captureRepository, repository, transactions, stored);
  }

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    ReentrantLock transactionLock = new ReentrantLock();
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              transactionLock.lock();
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              transactionLock.unlock();
              return null;
            })
        .when(transactions)
        .commit(any());
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              transactionLock.unlock();
              return null;
            })
        .when(transactions)
        .rollback(any());
    return transactions;
  }

  private static Acknowledgment validAcknowledgment(IssuerAuthoritySnapshot captured) {
    byte[] projection = projectionJson(captured, APPLIED_AT).getBytes(StandardCharsets.UTF_8);
    String requestDigest =
        Acknowledgment.requestDigestFor(
            ISSUER_ID,
            GAME_SESSION_ID,
            PROJECTION_KEY,
            OPERATION_ID,
            REQUEST_ID,
            1,
            captureDigest(),
            projectionJson(captured, APPLIED_AT));
    return new Acknowledgment(
        OTHER_OPERATION_ID,
        OPERATION_ID,
        REQUEST_ID,
        ISSUER_ID,
        GAME_SESSION_ID,
        PROJECTION_KEY,
        1,
        captureDigest(),
        1,
        requestDigest,
        projection,
        sha256(projection));
  }

  private static Receipt capture(
      UUID operationId, UUID requestId, IssuerAuthoritySnapshot captured) {
    return new Receipt(
        operationId,
        requestId,
        ISSUER_ID,
        GAME_SESSION_ID,
        PROJECTION_KEY,
        1,
        Receipt.requestDigestFor(ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, requestId),
        captured);
  }

  private static String captureDigest() {
    return Receipt.requestDigestFor(ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, REQUEST_ID);
  }

  private static IssuerAuthoritySnapshot baseline() {
    return new IssuerAuthoritySnapshot(ISSUER_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty());
  }

  private static IssuerAuthoritySnapshot snapshot(
      long generation, long sourceVersion, long sequence, IssuerGenerationAuthorityEvent event) {
    return new IssuerAuthoritySnapshot(
        ISSUER_ID, generation, sourceVersion, STREAM_KEY, sequence, Optional.of(event));
  }

  private static IssuerGenerationAuthorityEvent event(
      UUID requestId, long sequence, long generation, long sourceVersion) {
    String request = requestId.toString();
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-issuer-authority-event-v1:" + request,
            "requestId",
            request,
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
  }

  private static String projectionJson(IssuerAuthoritySnapshot source, String appliedAt) {
    Map<String, Object> projection = new LinkedHashMap<>();
    projection.put("schemaVersion", "game-session-auth-issuer-projection/v1");
    projection.put("issuerId", source.issuerId());
    projection.put("lastAppliedIssuerGeneration", Long.toString(source.issuerAuthGeneration()));
    projection.put("lastAppliedSourceOutboxSequence", Long.toString(source.outboxSequence()));
    projection.put("outboxStreamKey", source.outboxStreamKey());
    if (source.latestEvent().isPresent()) {
      IssuerGenerationAuthorityEvent event = source.latestEvent().orElseThrow();
      projection.put("lastAppliedSourceEventId", event.eventId());
      projection.put("lastAppliedSourceEventDigest", event.eventDigest());
      projection.put(
          "appliedSourceEvidence",
          Map.of(Long.toString(source.outboxSequence()), event.canonicalJson()));
    } else {
      projection.put("appliedSourceEvidence", Map.of());
    }
    projection.put("appliedAt", appliedAt);
    return writeJson(projection);
  }

  private static String writeJson(Map<String, Object> value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException(
          "Issuer projection test fixture could not be serialized", failure);
    }
  }

  private static String independentDigest(String... fields) throws Exception {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
      framed.write(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
      framed.write(':');
      framed.write(encoded);
    }
    return java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private record Fixture(
      AccountIssuerProjectionAcknowledgmentService service,
      AccountIssuerAuthorityEventProducer source,
      AccountIssuerProjectionReconciliationRepository captureRepository,
      AccountIssuerProjectionAcknowledgmentRepository repository,
      PlatformTransactionManager transactions,
      AtomicReference<Acknowledgment> stored) {
    private Acknowledgment acknowledge(IssuerAuthoritySnapshot captured, String appliedAt) {
      return service.acknowledge(
          ISSUER_ID,
          GAME_SESSION_ID,
          OPERATION_ID,
          REQUEST_ID,
          1,
          captureDigest(),
          projectionJson(captured, appliedAt));
    }

    private AtomicReference<Acknowledgment> store() {
      return stored;
    }
  }
}
