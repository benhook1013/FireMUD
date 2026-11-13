package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ReadRequest;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameAuthoredWorldSourceDeliveryServiceTest {
  private static final String NAMESPACE = "authored-world-test";
  private static final UUID SOURCE_OPERATION_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REGISTRATION_REQUEST_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INTAKE_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID WORLD_OPERATION_ID = uuid("55555555-5555-4555-8555-555555555555");

  private final GameAuthoredWorldSourceDeliveryRepository repository =
      mock(GameAuthoredWorldSourceDeliveryRepository.class);
  private final WorldAuthoredSourceIntakeClient worldClient =
      mock(WorldAuthoredSourceIntakeClient.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final GameAuthoredWorldSourceDeliveryService service =
      new GameAuthoredWorldSourceDeliveryService(
          repository, worldClient, transactionManager, NAMESPACE);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactCompletedRetryReturnsDurableReceiptWithoutCallingWorld() {
    DeliveryClaim completed = claim(Optional.of(receipt(request(), WORLD_OPERATION_ID, 'a')));
    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.of(completed));

    DeliveryClaim result = service.deliver(SOURCE_OPERATION_ID);

    assertThat(result).isEqualTo(completed);
    verify(repository).read(SOURCE_OPERATION_ID);
    verify(repository, never()).acknowledge(any(), any());
    verifyNoInteractions(worldClient);
    assertThat(transactionManager.beginCount).hasValue(0);
  }

  @Test
  void missingDeliveryClaimIsDeniedWithoutCallingWorld() {
    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryService.DeliveryNotFoundException.class);

    verify(repository).read(SOURCE_OPERATION_ID);
    verify(repository, never()).acknowledge(any(), any());
    verifyNoInteractions(worldClient);
  }

  @Test
  void wrongNamespaceClaimIsDeniedBeforeCallingWorld() {
    DeliveryClaim wrongNamespace = claim(NAMESPACE, Optional.empty());
    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.of(wrongNamespace));
    GameAuthoredWorldSourceDeliveryService otherNamespaceService =
        new GameAuthoredWorldSourceDeliveryService(
            repository, worldClient, transactionManager, "different-namespace");

    assertThatThrownBy(() -> otherNamespaceService.deliver(SOURCE_OPERATION_ID))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryService.DeliveryConflictException.class);

    verify(repository).read(SOURCE_OPERATION_ID);
    verify(repository, never()).acknowledge(any(), any());
    verifyNoInteractions(worldClient);
  }

  @Test
  void writableAmbientTransactionIsDeniedBeforeOwnerReadOrWorldCall() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(repository, worldClient);
  }

  @Test
  void mismatchedWorldIntakeOrReadbackNeverAcknowledges() {
    DeliveryClaim pending = claim(Optional.empty());
    CommittedReceipt expected = receipt(pending.request(), WORLD_OPERATION_ID, 'a');
    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.of(pending));
    when(worldClient.intake(pending.request()))
        .thenReturn(receipt(pending.request(), uuid("66666666-6666-4666-8666-666666666666"), 'b'));
    when(worldClient.read(any(ReadRequest.class))).thenReturn(expected);

    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryService.DeliveryConflictException.class)
        .hasMessageContaining("same complete receipt");
    verify(repository, never()).acknowledge(any(), any());

    org.mockito.Mockito.reset(repository, worldClient);
    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.of(pending));
    when(worldClient.intake(pending.request())).thenReturn(expected);
    when(worldClient.read(any(ReadRequest.class)))
        .thenReturn(receipt(pending.request(), uuid("77777777-7777-4777-8777-777777777777"), 'c'));

    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryService.DeliveryConflictException.class)
        .hasMessageContaining("same complete receipt");

    verify(worldClient).intake(pending.request());
    verify(worldClient).read(any(ReadRequest.class));
    verify(repository, never()).acknowledge(any(), any());
  }

  @Test
  void ambiguousWorldIntakeOrReadbackLeavesClaimPending() {
    DeliveryClaim pending = claim(Optional.empty());
    CommittedReceipt expected = receipt(pending.request(), WORLD_OPERATION_ID, 'a');

    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.of(pending));
    when(worldClient.intake(pending.request()))
        .thenThrow(new IllegalStateException("lost intake reply"));
    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .hasMessageContaining("lost intake reply");
    verify(repository, never()).acknowledge(any(), any());

    org.mockito.Mockito.reset(repository, worldClient);
    when(repository.read(SOURCE_OPERATION_ID)).thenReturn(Optional.of(pending));
    when(worldClient.intake(pending.request())).thenReturn(expected);
    when(worldClient.read(any(ReadRequest.class)))
        .thenThrow(new IllegalStateException("World readback unavailable"));

    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .hasMessageContaining("World readback unavailable");

    verify(repository, never()).acknowledge(any(), any());
  }

  @Test
  void exactWorldReceiptIsAcknowledgedInShortTransactionAndReadBackAfterCommit() {
    DeliveryClaim pending = claim(Optional.empty());
    CommittedReceipt worldReceipt = receipt(pending.request(), WORLD_OPERATION_ID, 'a');
    DeliveryClaim acknowledged = claim(Optional.of(worldReceipt));
    AtomicInteger reads = new AtomicInteger();
    AtomicBoolean acknowledgementWasTransactional = new AtomicBoolean();
    AtomicBoolean readbackWasOutsideTransaction = new AtomicBoolean();
    AtomicBoolean worldCallsWereOutsideTransaction = new AtomicBoolean(true);
    when(repository.read(SOURCE_OPERATION_ID))
        .thenAnswer(
            invocation -> {
              if (reads.incrementAndGet() == 1) {
                return Optional.of(pending);
              }
              readbackWasOutsideTransaction.set(
                  !TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of(acknowledged);
            });
    when(worldClient.intake(pending.request()))
        .thenAnswer(
            invocation -> {
              worldCallsWereOutsideTransaction.compareAndSet(
                  true, !TransactionSynchronizationManager.isActualTransactionActive());
              return worldReceipt;
            });
    when(worldClient.read(any(ReadRequest.class)))
        .thenAnswer(
            invocation -> {
              worldCallsWereOutsideTransaction.compareAndSet(
                  true, !TransactionSynchronizationManager.isActualTransactionActive());
              return worldReceipt;
            });
    when(repository.acknowledge(pending, worldReceipt))
        .thenAnswer(
            invocation -> {
              acknowledgementWasTransactional.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return acknowledged;
            });

    DeliveryClaim result = service.deliver(SOURCE_OPERATION_ID);

    assertThat(result).isEqualTo(acknowledged);
    assertThat(reads).hasValue(2);
    assertThat(acknowledgementWasTransactional).isTrue();
    assertThat(readbackWasOutsideTransaction).isTrue();
    assertThat(worldCallsWereOutsideTransaction).isTrue();
    assertThat(transactionManager.definition.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.definition.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.definition.isReadOnly()).isFalse();
    ArgumentCaptor<ReadRequest> readRequest = ArgumentCaptor.forClass(ReadRequest.class);
    InOrder remoteOrder = inOrder(worldClient);
    remoteOrder.verify(worldClient).intake(pending.request());
    remoteOrder.verify(worldClient).read(readRequest.capture());
    assertThat(readRequest.getValue().binding()).isEqualTo(pending.request());
    assertThat(readRequest.getValue().requestId())
        .isNotEqualTo(pending.request().intakeRequestId())
        .isNotEqualTo(pending.source().operationId());
    verify(repository).acknowledge(pending, worldReceipt);
  }

  @Test
  void lostAcknowledgementRecoversDurablePriorBeforeAnyRedelivery() {
    DeliveryClaim pending = claim(Optional.empty());
    CommittedReceipt worldReceipt = receipt(pending.request(), WORLD_OPERATION_ID, 'a');
    DeliveryClaim acknowledged = claim(Optional.of(worldReceipt));
    AtomicInteger reads = new AtomicInteger();
    when(repository.read(SOURCE_OPERATION_ID))
        .thenAnswer(
            invocation -> Optional.of(reads.incrementAndGet() == 1 ? pending : acknowledged));
    when(worldClient.intake(pending.request())).thenReturn(worldReceipt);
    when(worldClient.read(any(ReadRequest.class))).thenReturn(worldReceipt);
    when(repository.acknowledge(pending, worldReceipt))
        .thenThrow(new IllegalStateException("commit acknowledgement was lost"));

    DeliveryClaim recovered = service.deliver(SOURCE_OPERATION_ID);
    assertThat(recovered).isEqualTo(acknowledged);
    DeliveryClaim retry = service.deliver(SOURCE_OPERATION_ID);

    assertThat(retry).isEqualTo(acknowledged);
    verify(worldClient).intake(pending.request());
    verify(worldClient).read(any(ReadRequest.class));
    verify(repository).acknowledge(pending, worldReceipt);
  }

  @Test
  void matchingConcurrentReceiptIsReturnedAndChangedReceiptIsDenied() {
    DeliveryClaim pending = claim(Optional.empty());
    CommittedReceipt worldReceipt = receipt(pending.request(), WORLD_OPERATION_ID, 'a');
    DeliveryClaim concurrentWinner = claim(Optional.of(worldReceipt));
    when(repository.read(SOURCE_OPERATION_ID))
        .thenReturn(Optional.of(pending), Optional.of(concurrentWinner));
    when(worldClient.intake(pending.request())).thenReturn(worldReceipt);
    when(worldClient.read(any(ReadRequest.class))).thenReturn(worldReceipt);
    when(repository.acknowledge(pending, worldReceipt)).thenReturn(concurrentWinner);

    assertThat(service.deliver(SOURCE_OPERATION_ID)).isEqualTo(concurrentWinner);

    org.mockito.Mockito.reset(repository, worldClient);
    CommittedReceipt otherReceipt = receipt(pending.request(), WORLD_OPERATION_ID, 'b');
    DeliveryClaim changedWinner = claim(Optional.of(otherReceipt));
    when(repository.read(SOURCE_OPERATION_ID))
        .thenReturn(Optional.of(pending), Optional.of(changedWinner));
    when(worldClient.intake(pending.request())).thenReturn(worldReceipt);
    when(worldClient.read(any(ReadRequest.class))).thenReturn(worldReceipt);
    when(repository.acknowledge(pending, worldReceipt))
        .thenThrow(new IllegalStateException("different World receipt is already acknowledged"));

    assertThat(otherReceipt).isNotEqualTo(worldReceipt);
    assertThatThrownBy(() -> service.deliver(SOURCE_OPERATION_ID))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryService.DeliveryConflictException.class)
        .hasMessageContaining("different World receipt won");
    verify(repository).acknowledge(pending, worldReceipt);
  }

  private static DeliveryClaim claim(Optional<CommittedReceipt> acknowledgedReceipt) {
    return claim(NAMESPACE, acknowledgedReceipt);
  }

  private static DeliveryClaim claim(
      String namespace, Optional<CommittedReceipt> acknowledgedReceipt) {
    AuthoredWorldSourceEvidence source = source(namespace);
    IntakeRequest request =
        new IntakeRequest(
            1,
            namespace,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    return new DeliveryClaim(source, request, acknowledgedReceipt);
  }

  private static AuthoredWorldSourceEvidence source(String namespace) {
    String tenantSlug = "authored-tenant";
    String worldSlug = "violet-wilds";
    String displayName = "Violet Wilds";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, REGISTRATION_REQUEST_ID, TENANT_ID, tenantSlug, worldSlug, displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        tenantSlug,
        worldSlug,
        displayName,
        17L,
        "authored-tenant-key",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            tenantSlug,
            worldSlug,
            displayName,
            17L,
            "authored-tenant-key",
            "NEW_GAME_ROW"));
  }

  private static IntakeRequest request() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE);
    return new IntakeRequest(
        1,
        NAMESPACE,
        INTAKE_REQUEST_ID,
        TENANT_ID,
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest());
  }

  private static CommittedReceipt receipt(
      IntakeRequest request, UUID operationId, char receiptMarker) {
    return new CommittedReceipt(
        request.schemaVersion(),
        request.targetNamespace(),
        request.intakeRequestId(),
        operationId,
        request.canonicalTenantId(),
        request.worldSlug(),
        request.sourceOperationId(),
        request.expectedSourceEvidenceDigest(),
        WorldAuthoredSourceIntakeGrpcCodec.requestDigest(request),
        digest(receiptMarker));
  }

  private static String digest(char marker) {
    return "sha256:" + String.valueOf(marker).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    private TransactionDefinition definition;
    private final AtomicInteger beginCount = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition transactionDefinition) {
      definition = transactionDefinition;
      beginCount.incrementAndGet();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
