package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

class AccountIssuerProjectionReconciliationServiceTest {
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String OTHER_ISSUER_ID = "https://other.example.test/issuer";
  private static final String GAME_SESSION_ID =
      "spiffe://firemud/ns/account-unit/sa/game-session-service";
  private static final String OTHER_GAME_SESSION_ID =
      "spiffe://firemud/ns/other-unit/sa/game-session-service";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final UUID REQUEST_ID = UUID.fromString("a3a69671-8149-4d97-86f8-25a2582fdd68");
  private static final UUID OPERATION_ID = UUID.fromString("91b0eb2d-f7b1-4d88-8db4-2b81cacfc96a");

  @Test
  void capturesPristineBaselineAndReturnsOnlyAfterSeparateExactPostCommitReadback() {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository repository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    IssuerAuthoritySnapshot baseline = baseline();
    AtomicReference<Receipt> stored = new AtomicReference<>();
    when(source.readCurrentForProjectionReconciliation(ISSUER_ID)).thenReturn(baseline, baseline);
    when(repository.findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(repository.insert(any(Receipt.class)))
        .thenAnswer(
            invocation -> {
              Receipt candidate = invocation.getArgument(0);
              stored.set(candidate);
              return candidate;
            });

    Receipt receipt =
        service(source, repository, transactions).capture(ISSUER_ID, GAME_SESSION_ID, REQUEST_ID);

    assertThat(receipt.operationId()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(receipt.requestId()).isEqualTo(REQUEST_ID);
    assertThat(receipt.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(receipt.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_ID);
    assertThat(receipt.projectionKey()).isEqualTo(PROJECTION_KEY);
    assertThat(receipt.requestDigestVersion()).isEqualTo(1);
    assertThat(receipt.requestDigest()).matches("[0-9a-f]{64}");
    assertThat(receipt.capturedSource()).isEqualTo(baseline);
    verify(transactions, times(2)).getTransaction(any(TransactionDefinition.class));
    verify(transactions, times(2)).commit(any());
    verify(repository, times(1)).insert(any(Receipt.class));
    verify(source, times(2)).readCurrentForProjectionReconciliation(ISSUER_ID);
    verify(source, never()).advance(anyString(), any(), anyLong(), anyLong());
    var order = inOrder(source, repository);
    order.verify(source).readCurrentForProjectionReconciliation(ISSUER_ID);
    order.verify(repository).findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID);
  }

  @Test
  void exactRetryReturnsOriginalPositiveSnapshotAfterSourceHasAdvanced() {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository repository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    IssuerGenerationAuthorityEvent capturedEvent = event(REQUEST_ID, 1L, 2L, 2L);
    IssuerAuthoritySnapshot captured =
        new IssuerAuthoritySnapshot(ISSUER_ID, 2L, 2L, STREAM_KEY, 1L, Optional.of(capturedEvent));
    IssuerGenerationAuthorityEvent laterEvent = event(UUID.randomUUID(), 2L, 3L, 3L);
    IssuerAuthoritySnapshot current =
        new IssuerAuthoritySnapshot(ISSUER_ID, 3L, 3L, STREAM_KEY, 2L, Optional.of(laterEvent));
    Receipt original = receipt(OPERATION_ID, REQUEST_ID, GAME_SESSION_ID, captured);
    when(source.readCurrentForProjectionReconciliation(ISSUER_ID)).thenReturn(current, current);
    when(source.readCommittedEventForProjectionReconciliation(ISSUER_ID, 1L))
        .thenReturn(
            new IssuerAuthorityEventReadback(current, capturedEvent),
            new IssuerAuthorityEventReadback(current, capturedEvent));
    when(repository.findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID))
        .thenReturn(Optional.of(original), Optional.of(original));

    Receipt recovered =
        service(source, repository, transactions).capture(ISSUER_ID, GAME_SESSION_ID, REQUEST_ID);

    assertThat(recovered.operationId()).isEqualTo(OPERATION_ID);
    assertThat(recovered.capturedSource()).isEqualTo(captured);
    verify(repository, never()).insert(any(Receipt.class));
    verify(source, times(2)).readCurrentForProjectionReconciliation(ISSUER_ID);
    verify(source, times(2)).readCommittedEventForProjectionReconciliation(ISSUER_ID, 1L);
    verify(source, never()).advance(anyString(), any(), anyLong(), anyLong());
  }

  @Test
  void changedOriginalCallerBindingConflictsWithoutReceiptReplacementOrSourceMutation() {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository repository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    IssuerAuthoritySnapshot current = baseline();
    Receipt prior = receipt(OPERATION_ID, REQUEST_ID, OTHER_GAME_SESSION_ID, current);
    when(source.readCurrentForProjectionReconciliation(ISSUER_ID)).thenReturn(current);
    when(repository.findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID)).thenReturn(Optional.of(prior));

    assertThatThrownBy(
            () ->
                service(source, repository, transactions)
                    .capture(ISSUER_ID, GAME_SESSION_ID, REQUEST_ID))
        .isInstanceOf(
            AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException.class)
        .hasMessageContaining("changed caller or projection bindings");

    verify(repository, never()).insert(any(Receipt.class));
    verify(source, never()).readCommittedEventForProjectionReconciliation(anyString(), anyLong());
    verify(source, never()).advance(anyString(), any(), anyLong(), anyLong());
    verify(transactions, never()).commit(any());
    verify(transactions, times(1)).rollback(any());
  }

  @Test
  void nilRequestWrongIssuerAndWrongCallerFailBeforeTransactionsOrOwnerAccess() {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository repository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    AccountIssuerProjectionReconciliationService service =
        service(source, repository, transactions);

    assertThatThrownBy(() -> service.capture(ISSUER_ID, GAME_SESSION_ID, new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    assertThatThrownBy(() -> service.capture(OTHER_ISSUER_ID, GAME_SESSION_ID, REQUEST_ID))
        .isInstanceOf(AccountIssuerAuthorityEventProducer.IssuerMismatchException.class);
    assertThatThrownBy(() -> service.capture(ISSUER_ID, OTHER_GAME_SESSION_ID, REQUEST_ID))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("exact authenticated Game Session");

    verifyNoInteractions(source, repository, transactions);
  }

  @Test
  void missingPostCommitReceiptReadbackFailsClosedAfterInsert() {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository repository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    IssuerAuthoritySnapshot baseline = baseline();
    AtomicReference<Receipt> stored = new AtomicReference<>();
    AtomicInteger lookup = new AtomicInteger();
    when(source.readCurrentForProjectionReconciliation(ISSUER_ID)).thenReturn(baseline, baseline);
    when(repository.findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID))
        .thenAnswer(
            invocation ->
                lookup.incrementAndGet() < 3
                    ? Optional.ofNullable(stored.get())
                    : Optional.empty());
    when(repository.insert(any(Receipt.class)))
        .thenAnswer(
            invocation -> {
              Receipt candidate = invocation.getArgument(0);
              stored.set(candidate);
              return candidate;
            });

    assertThatThrownBy(
            () ->
                service(source, repository, transactions)
                    .capture(ISSUER_ID, GAME_SESSION_ID, REQUEST_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Post-commit issuer reconciliation receipt readback is missing");

    verify(repository, times(1)).insert(any(Receipt.class));
    verify(transactions, times(1)).commit(any());
    verify(transactions, times(1)).rollback(any());
  }

  @Test
  void ambientTransactionIsRefusedBeforeSourceOrReceiptAccess() {
    AccountIssuerAuthorityEventProducer source = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationRepository repository =
        mock(AccountIssuerProjectionReconciliationRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  service(source, repository, transactions)
                      .capture(ISSUER_ID, GAME_SESSION_ID, REQUEST_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      verifyNoInteractions(source, repository, transactions);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(wasActive);
    }
  }

  @Test
  void repositoryRejectsMissingDslBeforeBothReadAndInsertSqlAccess() {
    AccountIssuerProjectionReconciliationRepository repository =
        new AccountIssuerProjectionReconciliationRepository(null);
    Receipt validReceipt = receipt(OPERATION_ID, REQUEST_ID, GAME_SESSION_ID, baseline());
    boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.findByIssuerAndRequestId(ISSUER_ID, REQUEST_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires a DSLContext");
      assertThatThrownBy(() -> repository.insert(validReceipt))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires a DSLContext");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(wasActive);
    }
  }

  @Test
  void requestDigestUsesTheFixedVersionedSixFieldUtf8ByteFraming() throws Exception {
    assertThat(Receipt.requestDigestFor(ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, REQUEST_ID))
        .isEqualTo("240027543295dc990703ec833578b423f1cdc51e91333d8b58c91d6c5f571d15");

    String unicodeCaller = "spiffe://firemud/ns/ä/sa/game-session-service";
    String digest = Receipt.requestDigestFor(ISSUER_ID, unicodeCaller, PROJECTION_KEY, REQUEST_ID);
    String expected =
        independentDigest(
            "issuer-projection-reconciliation-request/v1",
            "ISSUER_PROJECTION_SNAPSHOT_CAPTURE",
            ISSUER_ID,
            unicodeCaller,
            PROJECTION_KEY,
            REQUEST_ID.toString());

    assertThat(digest).isEqualTo(expected);
    assertThat(digest)
        .isNotEqualTo(
            Receipt.requestDigestFor(ISSUER_ID, GAME_SESSION_ID, PROJECTION_KEY, REQUEST_ID));
  }

  private static String independentDigest(String... fields) throws Exception {
    java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
    for (String field : fields) {
      byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
      framed.write(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
      framed.write(':');
      framed.write(encoded);
    }
    return java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
  }

  private static IssuerAuthoritySnapshot baseline() {
    return new IssuerAuthoritySnapshot(ISSUER_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty());
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

  private static Receipt receipt(
      UUID operationId,
      UUID requestId,
      String callerIdentity,
      IssuerAuthoritySnapshot capturedSource) {
    return new Receipt(
        operationId,
        requestId,
        ISSUER_ID,
        callerIdentity,
        PROJECTION_KEY,
        1,
        Receipt.requestDigestFor(ISSUER_ID, callerIdentity, PROJECTION_KEY, requestId),
        capturedSource);
  }

  private static AccountIssuerProjectionReconciliationService service(
      AccountIssuerAuthorityEventProducer source,
      AccountIssuerProjectionReconciliationRepository repository,
      PlatformTransactionManager transactions) {
    return new AccountIssuerProjectionReconciliationService(
        ISSUER_ID, GAME_SESSION_ID, source, repository, transactions);
  }

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    doNothing().when(transactions).commit(any());
    doNothing().when(transactions).rollback(any());
    return transactions;
  }
}
