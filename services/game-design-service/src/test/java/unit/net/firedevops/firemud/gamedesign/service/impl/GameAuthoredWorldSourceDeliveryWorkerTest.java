package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.gamedesign.config.GameAuthoredWorldSourceDeliveryProperties;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class GameAuthoredWorldSourceDeliveryWorkerTest {
  private static final String NAMESPACE = "authored-world-worker-test";

  @Test
  void boundedNamespacePagesAdvancePastAmbiguousClaimThenCycleForExactRetry() {
    GameAuthoredWorldSourceDeliveryRepository repository =
        mock(GameAuthoredWorldSourceDeliveryRepository.class);
    GameAuthoredWorldSourceDeliveryService deliveryService =
        mock(GameAuthoredWorldSourceDeliveryService.class);
    GameAuthoredWorldSourceDeliveryProperties properties =
        new GameAuthoredWorldSourceDeliveryProperties();
    properties.setBatchSize(1);
    DeliveryClaim first = claim(1);
    DeliveryClaim second = claim(2);
    when(repository.readPending(NAMESPACE, null, 1)).thenReturn(List.of(first), List.of(first));
    when(repository.readPending(NAMESPACE, first.request().intakeRequestId(), 1))
        .thenReturn(List.of(second));
    when(repository.readPending(NAMESPACE, second.request().intakeRequestId(), 1))
        .thenReturn(List.of());
    doThrow(new IllegalStateException("ambiguous remote response"))
        .when(deliveryService)
        .deliver(first.source().operationId());

    GameAuthoredWorldSourceDeliveryWorker worker =
        new GameAuthoredWorldSourceDeliveryWorker(
            repository, deliveryService, properties, NAMESPACE);

    assertThat(worker.runPass()).isOne();
    assertThat(worker.runPass()).isOne();
    assertThat(worker.runPass()).isZero();
    assertThat(worker.runPass()).isOne();

    InOrder pageOrder = inOrder(repository);
    pageOrder.verify(repository).readPending(NAMESPACE, null, 1);
    pageOrder.verify(repository).readPending(NAMESPACE, first.request().intakeRequestId(), 1);
    pageOrder.verify(repository).readPending(NAMESPACE, second.request().intakeRequestId(), 1);
    pageOrder.verify(repository).readPending(NAMESPACE, null, 1);
    InOrder dispatchOrder = inOrder(deliveryService);
    dispatchOrder.verify(deliveryService).deliver(first.source().operationId());
    dispatchOrder.verify(deliveryService).deliver(second.source().operationId());
    dispatchOrder.verify(deliveryService).deliver(first.source().operationId());
  }

  @Test
  void invalidPollingBoundsFailBeforeTheWorkerCanStart() {
    GameAuthoredWorldSourceDeliveryProperties properties =
        new GameAuthoredWorldSourceDeliveryProperties();
    properties.setBatchSize(101);

    assertThatThrownBy(
            () ->
                new GameAuthoredWorldSourceDeliveryWorker(
                    mock(GameAuthoredWorldSourceDeliveryRepository.class),
                    mock(GameAuthoredWorldSourceDeliveryService.class),
                    properties,
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batch size");
  }

  @Test
  void overlappingPassDoesNotClaimOrDispatchTheSamePageAgain() throws Exception {
    GameAuthoredWorldSourceDeliveryRepository repository =
        mock(GameAuthoredWorldSourceDeliveryRepository.class);
    GameAuthoredWorldSourceDeliveryService deliveryService =
        mock(GameAuthoredWorldSourceDeliveryService.class);
    GameAuthoredWorldSourceDeliveryProperties properties =
        new GameAuthoredWorldSourceDeliveryProperties();
    DeliveryClaim claim = claim(3);
    CountDownLatch pageReadStarted = new CountDownLatch(1);
    CountDownLatch releasePageRead = new CountDownLatch(1);
    when(repository.readPending(NAMESPACE, null, properties.getBatchSize()))
        .thenAnswer(
            invocation -> {
              pageReadStarted.countDown();
              if (!releasePageRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the pending page read");
              }
              return List.of(claim);
            });
    GameAuthoredWorldSourceDeliveryWorker worker =
        new GameAuthoredWorldSourceDeliveryWorker(
            repository, deliveryService, properties, NAMESPACE);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Integer> firstPass = executor.submit(worker::runPass);
    try {
      assertThat(pageReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(worker.runPass()).isZero();
      releasePageRead.countDown();
      assertThat(firstPass.get(5, TimeUnit.SECONDS)).isOne();
      verify(repository).readPending(NAMESPACE, null, properties.getBatchSize());
    } finally {
      releasePageRead.countDown();
      executor.shutdownNow();
    }
  }

  private static DeliveryClaim claim(int suffix) {
    String tail = String.format("%012d", suffix);
    UUID sourceOperationId = uuid("11111111-1111-4111-8111-" + tail);
    UUID registrationRequestId = uuid("22222222-2222-4222-8222-" + tail);
    UUID tenantId = uuid("33333333-3333-4333-8333-" + tail);
    UUID intakeRequestId = uuid("44444444-4444-4444-8444-" + tail);
    String tenantSlug = "tenant-" + suffix;
    String worldSlug = "world-" + suffix;
    String displayName = "World " + suffix;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            100L + suffix,
            "legacy-tenant-" + suffix,
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                registrationRequestId,
                sourceOperationId,
                requestDigest,
                tenantId,
                tenantSlug,
                worldSlug,
                displayName,
                100L + suffix,
                "legacy-tenant-" + suffix,
                "NEW_GAME_ROW"));
    IntakeRequest request =
        new IntakeRequest(
            1,
            NAMESPACE,
            intakeRequestId,
            tenantId,
            worldSlug,
            sourceOperationId,
            source.evidenceDigest());
    return new DeliveryClaim(source, request, Optional.empty());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
