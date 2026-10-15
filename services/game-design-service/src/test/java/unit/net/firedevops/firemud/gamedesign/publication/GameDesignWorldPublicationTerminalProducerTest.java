package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionGrpcCodec.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled input/call-order proof only; physical owner production is established separately. */
class GameDesignWorldPublicationTerminalProducerTest {
  private final GameDesignPublicationOperationService owner =
      mock(GameDesignPublicationOperationService.class);
  private final WorldPublicationTerminalCompletionClient client =
      mock(WorldPublicationTerminalCompletionClient.class);
  private GameDesignPublicationOperation operation;
  private GameDesignPublicationTerminalEvidence terminal;
  private GameDesignWorldPublicationTerminalProducer producer;

  @BeforeEach
  void setup() throws Exception {
    operation =
        IsolatedPublicationOperationFixtures.fresh(
            new TargetProof(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                19L,
                "tenant-key",
                42L,
                "tenant-key",
                "NEW_GAME_ROW"));
    terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.NO_PUBLICATION, null, null);
    producer =
        new GameDesignWorldPublicationTerminalProducer(
            owner, client, operation.world().request().targetNamespace());
    when(owner.readExact(
            operation.tenantKey(),
            operation.workflowId(),
            operation.versionId(),
            operation.selectionDigest()))
        .thenReturn(Optional.of(readback("NO_PUBLICATION", terminal.canonicalBytes())));
    when(client.complete(any())).thenReturn(terminal);
  }

  private GameDesignPublicationOperationRepository.Readback readback(String outcome, byte[] bytes) {
    return new GameDesignPublicationOperationRepository.Readback(
        operation, outcome, new byte[] {1}, bytes);
  }

  private GameDesignPublicationTerminalEvidence reconcile() {
    return producer.reconcile(
        operation.tenantKey(),
        operation.workflowId(),
        operation.versionId(),
        operation.selectionDigest(),
        Outcome.NO_PUBLICATION);
  }

  @Test
  void lostReplyRetriesOnlyCommittedOriginalBytesWithFreshCorrelation() {
    when(client.complete(any()))
        .thenThrow(new IllegalStateException("lost after World commit"))
        .thenReturn(terminal);
    assertThatThrownBy(this::reconcile).hasMessageContaining("lost after World commit");
    assertThat(reconcile().canonicalBytes()).isEqualTo(terminal.canonicalBytes());
    var calls = ArgumentCaptor.forClass(Request.class);
    verify(client, times(2)).complete(calls.capture());
    var first = calls.getAllValues().get(0);
    var retry = calls.getAllValues().get(1);
    assertThat(retry.requestId()).isNotEqualTo(first.requestId());
    assertThat(retry.originalOperation())
        .isEqualTo(first.originalOperation())
        .isEqualTo(operation.canonicalBytes());
    assertThat(retry.gameDesignTerminalEvidence())
        .isEqualTo(first.gameDesignTerminalEvidence())
        .isEqualTo(terminal.canonicalBytes());
    verify(owner, times(2))
        .readExact(
            operation.tenantKey(),
            operation.workflowId(),
            operation.versionId(),
            operation.selectionDigest());
  }

  @Test
  void absentPendingHistoricalOrChangedOutcomeNeverCallsWorld() {
    for (var value :
        java.util.List.of(
            Optional.<GameDesignPublicationOperationRepository.Readback>empty(),
            Optional.of(readback("PENDING", null)),
            Optional.of(readback("NO_PUBLICATION", null)),
            Optional.of(readback("PUBLISHED", terminal.canonicalBytes())),
            Optional.of(readback("NO_PUBLICATION", new byte[] {1})))) {
      when(owner.readExact(
              operation.tenantKey(),
              operation.workflowId(),
              operation.versionId(),
              operation.selectionDigest()))
          .thenReturn(value);
      assertThatThrownBy(this::reconcile).isInstanceOf(RuntimeException.class);
    }
    verifyNoInteractions(client);
  }

  @Test
  void wrongNamespaceScopeExpectedOutcomeOrReturnedEvidenceRemainUnresolved() {
    var wrongNamespace = new GameDesignWorldPublicationTerminalProducer(owner, client, "other");
    assertThatThrownBy(
            () ->
                wrongNamespace.reconcile(
                    operation.tenantKey(),
                    operation.workflowId(),
                    operation.versionId(),
                    operation.selectionDigest(),
                    Outcome.NO_PUBLICATION))
        .isInstanceOf(IllegalStateException.class);
    when(owner.readExact(
            "other", operation.workflowId(), operation.versionId(), operation.selectionDigest()))
        .thenReturn(Optional.of(readback("NO_PUBLICATION", terminal.canonicalBytes())));
    assertThatThrownBy(
            () ->
                producer.reconcile(
                    "other",
                    operation.workflowId(),
                    operation.versionId(),
                    operation.selectionDigest(),
                    Outcome.NO_PUBLICATION))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                producer.reconcile(
                    operation.tenantKey(),
                    operation.workflowId(),
                    operation.versionId(),
                    operation.selectionDigest(),
                    Outcome.PUBLISHED))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(client);
    var changed = mock(GameDesignPublicationTerminalEvidence.class);
    when(changed.canonicalBytes()).thenReturn(new byte[] {1});
    when(client.complete(any())).thenReturn(changed);
    assertThatThrownBy(this::reconcile).hasMessage("WORLD_PUBLICATION_TERMINAL_PENDING");
  }

  @Test
  void ambientTransactionOrSynchronizationIsDeniedBeforeOwnerOrNetwork() {
    for (boolean actual : new boolean[] {true, false}) {
      try {
        if (actual) TransactionSynchronizationManager.setActualTransactionActive(true);
        else TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(this::reconcile)
            .hasMessage("Independent World publication handoff required");
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verifyNoInteractions(owner, client);
  }

  @Test
  void concurrentReconciliationsCanBothEnterOwnerRead() throws Exception {
    var readsEntered = new CountDownLatch(2);
    var releaseReads = new CountDownLatch(1);
    when(owner.readExact(
            operation.tenantKey(),
            operation.workflowId(),
            operation.versionId(),
            operation.selectionDigest()))
        .thenAnswer(
            ignored -> {
              readsEntered.countDown();
              if (!releaseReads.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting to release owner read");
              return Optional.of(readback("NO_PUBLICATION", terminal.canonicalBytes()));
            });
    var workers = daemonPool(2);
    try {
      var first = workers.submit(this::reconcile);
      var second = workers.submit(this::reconcile);
      assertThat(readsEntered.await(5, TimeUnit.SECONDS)).isTrue();
      releaseReads.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).canonicalBytes())
          .isEqualTo(terminal.canonicalBytes());
      assertThat(second.get(5, TimeUnit.SECONDS).canonicalBytes())
          .isEqualTo(terminal.canonicalBytes());
      verify(owner, times(2))
          .readExact(
              operation.tenantKey(),
              operation.workflowId(),
              operation.versionId(),
              operation.selectionDigest());
      verify(client, times(2)).complete(any());
    } finally {
      releaseReads.countDown();
      stopWorkers(workers);
    }
  }

  @Test
  void closeRejectsNewCallsAndDrainsAdmittedReconciliationBeforeClosingClient() throws Exception {
    var readEntered = new CountDownLatch(1);
    var releaseRead = new CountDownLatch(1);
    when(owner.readExact(
            operation.tenantKey(),
            operation.workflowId(),
            operation.versionId(),
            operation.selectionDigest()))
        .thenAnswer(
            ignored -> {
              readEntered.countDown();
              if (!releaseRead.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting to release owner read");
              return Optional.of(readback("NO_PUBLICATION", terminal.canonicalBytes()));
            });
    var workers = daemonPool(1);
    var closeStarted = new CountDownLatch(1);
    var closeFailure = new AtomicReference<Throwable>();
    var interruptRestored = new AtomicBoolean();
    var closer =
        daemonThread(
            () -> {
              closeStarted.countDown();
              try {
                producer.close();
              } catch (Throwable failure) {
                closeFailure.set(failure);
              } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
              }
            });
    try {
      var activeReconciliation = workers.submit(this::reconcile);
      assertThat(readEntered.await(5, TimeUnit.SECONDS)).isTrue();
      closer.start();
      assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(awaitThreadState(closer, Thread.State.WAITING)).isTrue();

      closer.interrupt();
      assertThat(awaitThreadState(closer, Thread.State.WAITING)).isTrue();
      assertThatThrownBy(this::reconcile).hasMessageContaining("closed");
      verify(owner)
          .readExact(
              operation.tenantKey(),
              operation.workflowId(),
              operation.versionId(),
              operation.selectionDigest());
      verify(client, never()).close();

      releaseRead.countDown();
      assertThat(activeReconciliation.get(5, TimeUnit.SECONDS).canonicalBytes())
          .isEqualTo(terminal.canonicalBytes());
      closer.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(closer.isAlive()).isFalse();
      assertThat(closeFailure.get()).isNull();
      assertThat(interruptRestored.get()).isTrue();

      producer.close();
      verify(client, times(1)).close();
    } finally {
      releaseRead.countDown();
      stopWorkers(workers);
      if (closer.getState() != Thread.State.NEW && closer.isAlive()) {
        closer.interrupt();
        closer.join(TimeUnit.SECONDS.toMillis(5));
      }
    }
  }

  @Test
  void ownerAndWorldFailuresReleaseAdmissionsForClose() throws Exception {
    when(owner.readExact(
            operation.tenantKey(),
            operation.workflowId(),
            operation.versionId(),
            operation.selectionDigest()))
        .thenThrow(new IllegalStateException("owner read failed"));
    assertThatThrownBy(this::reconcile).hasMessage("owner read failed");

    doReturn(Optional.of(readback("NO_PUBLICATION", terminal.canonicalBytes())))
        .when(owner)
        .readExact(
            operation.tenantKey(),
            operation.workflowId(),
            operation.versionId(),
            operation.selectionDigest());
    when(client.complete(any())).thenThrow(new IllegalStateException("World completion failed"));
    assertThatThrownBy(this::reconcile).hasMessage("World completion failed");

    doReturn(terminal).when(client).complete(any());
    var workers = daemonPool(1);
    try {
      var close =
          workers.submit(
              () -> {
                producer.close();
                return null;
              });
      close.get(5, TimeUnit.SECONDS);
      verify(client).close();
    } finally {
      stopWorkers(workers);
    }
  }

  @Test
  void explicitCloseIsIdempotentAndPreventsEffects() throws Exception {
    producer.close();
    producer.close();
    verify(client).close();
    assertThatThrownBy(this::reconcile).hasMessageContaining("closed");
    verifyNoInteractions(owner);
  }

  private static ExecutorService daemonPool(int size) {
    return Executors.newFixedThreadPool(
        size, GameDesignWorldPublicationTerminalProducerTest::daemonThread);
  }

  private static Thread daemonThread(Runnable task) {
    var thread = new Thread(task);
    thread.setDaemon(true);
    return thread;
  }

  private static void stopWorkers(ExecutorService workers) throws InterruptedException {
    workers.shutdownNow();
    assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static boolean awaitThreadState(Thread thread, Thread.State expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && thread.isAlive()) {
      if (thread.getState() == expected) return true;
      Thread.onSpinWait();
    }
    return thread.getState() == expected;
  }
}
