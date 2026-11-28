package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountIssuerAuthoritySourceClient;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec.ReadRequest;
import org.junit.jupiter.api.Test;

class GameSessionIssuerGenerationProjectionBootstrapTest {
  private static final UUID OPERATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String NAMESPACE = "test";
  private static final String CALLER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  private final AccountIssuerAuthoritySourceClient sourceClient =
      mock(AccountIssuerAuthoritySourceClient.class);
  private final GameSessionIssuerGenerationProjectionBootstrap bootstrap =
      new GameSessionIssuerGenerationProjectionBootstrap(sourceClient);

  @Test
  void sequenceZeroRetainsAccountEnrollmentCheckpointWithNoEventFieldsOrDuplicateEvidence() {
    var request = new ReadRequest(OPERATION, NAMESPACE, ISSUER, Optional.empty());
    var source = source("1", "0", Optional.empty());
    when(sourceClient.read(request)).thenReturn(source);

    var candidate = bootstrap.prepare(request);

    assertThat(candidate.sourceCheckpoint()).isEqualTo(source);
    assertThat(candidate.projectionCheckpoint())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "schemaVersion",
                "game-session-auth-issuer-projection/v1",
                "issuerId",
                ISSUER,
                "lastAppliedIssuerGeneration",
                "1",
                "sourceVersion",
                "1",
                "outboxStreamKey",
                STREAM,
                "lastAppliedSourceOutboxSequence",
                "0",
                "reconciliationOperationId",
                OPERATION.toString(),
                "appliedSourceEvidence",
                List.of()));
    verify(sourceClient).read(request);
    verifyNoMoreInteractions(sourceClient);
  }

  @Test
  void positiveCheckpointRetainsOriginalEventAndExactReplayEvidence() {
    String eventId = "retained-source-event-7";
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                eventId, eventId, STREAM, "7", ISSUER, "8", "8", "SIGNER_COMPROMISE"));
    var source = source("8", "7", Optional.of(event.canonicalJson()));
    var request = new ReadRequest(OPERATION, NAMESPACE, ISSUER, Optional.empty());
    when(sourceClient.read(request)).thenReturn(source);
    var replayRequest = new ReadRequest(OPERATION, NAMESPACE, ISSUER, Optional.of(source));
    when(sourceClient.read(replayRequest)).thenReturn(source);

    var candidate = bootstrap.prepare(request);
    var replay = bootstrap.prepare(replayRequest);

    assertThat(candidate.sourceCheckpoint()).isEqualTo(source);
    assertThat(candidate.sourceCheckpoint().sourceEvent()).contains(event.canonicalJson());
    assertThat(candidate.projectionCheckpoint())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "schemaVersion",
                "game-session-auth-issuer-projection/v1",
                "issuerId",
                ISSUER,
                "lastAppliedIssuerGeneration",
                "8",
                "sourceVersion",
                "8",
                "outboxStreamKey",
                STREAM,
                "lastAppliedSourceOutboxSequence",
                "7",
                "reconciliationOperationId",
                OPERATION.toString(),
                "lastAppliedSourceEventId",
                eventId,
                "lastAppliedSourceEventDigest",
                event.eventDigest(),
                "appliedSourceEvidence",
                List.of(
                    Map.of(
                        "outboxStreamKey",
                        STREAM,
                        "outboxSequence",
                        "7",
                        "eventId",
                        eventId,
                        "eventDigest",
                        event.eventDigest()))));
    assertThat(replay.sourceCheckpoint()).isEqualTo(source);
    assertThat(replay.projectionCheckpoint()).isEqualTo(candidate.projectionCheckpoint());
    assertThatThrownBy(candidate.projectionCheckpoint()::clear)
        .isInstanceOf(UnsupportedOperationException.class);
    verify(sourceClient).read(request);
    verify(sourceClient).read(replayRequest);
    verifyNoMoreInteractions(sourceClient);
  }

  @Test
  void refusedExactReplayDoesNotRetryAgainstAnotherCheckpoint() {
    var source = source("1", "0", Optional.empty());
    var request = new ReadRequest(OPERATION, NAMESPACE, ISSUER, Optional.of(source));
    var refusal =
        Status.FAILED_PRECONDITION
            .withDescription("Account issuer source differs from the exact expected snapshot")
            .asRuntimeException();
    when(sourceClient.read(request)).thenThrow(refusal);

    assertThatThrownBy(() -> bootstrap.prepare(request)).isSameAs(refusal);
    verify(sourceClient).read(request);
    verifyNoMoreInteractions(sourceClient);
  }

  private static AccountIssuerSourceSnapshotEvidence source(
      String generation, String sequence, Optional<String> event) {
    return new AccountIssuerSourceSnapshotEvidence(
        OPERATION, NAMESPACE, CALLER, ISSUER, generation, generation, STREAM, sequence, event);
  }
}
