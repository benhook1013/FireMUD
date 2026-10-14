package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
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
  void explicitCloseIsIdempotentAndPreventsEffects() throws Exception {
    producer.close();
    producer.close();
    verify(client).close();
    assertThatThrownBy(this::reconcile).hasMessageContaining("closed");
    verifyNoInteractions(owner);
  }
}
