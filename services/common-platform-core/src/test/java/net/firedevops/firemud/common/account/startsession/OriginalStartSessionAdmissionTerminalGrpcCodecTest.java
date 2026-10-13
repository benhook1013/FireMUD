package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalGrpcCodec;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;

/** Synthetic carrier tests; the fixture does not represent a genuine Game Session terminal. */
class OriginalStartSessionAdmissionTerminalGrpcCodecTest {
  private static final UUID OTHER_HOLD_FENCE =
      UUID.fromString("c3527a86-eae1-4a1e-b8a6-13c831f7a665");
  private static final String PROOF_DIGEST = "sha256:" + "d".repeat(64);

  @Test
  void roundTripsCompleteAccountProtectionAndBothTerminalOutcomes() {
    var request = request();
    var wireRequest = OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(request);
    assertThat(OriginalStartSessionAdmissionTerminalGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);

    var committed =
        result(request, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    var committedResponse = OriginalStartSessionAdmissionTerminalGrpcCodec.toResponse(committed);
    assertThat(committedResponse.getRequest())
        .isEqualTo(OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(request));
    var recoveredCommitted =
        OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(request, committedResponse);
    assertThat(recoveredCommitted.request().canonicalBytes())
        .containsExactly(request.canonicalBytes());
    assertThat(recoveredCommitted.canonicalBytes()).containsExactly(committed.canonicalBytes());
    assertThat(recoveredCommitted.ownerProof().outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);

    var aborted = result(request, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    var recoveredAborted =
        OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(
            request, OriginalStartSessionAdmissionTerminalGrpcCodec.toResponse(aborted));
    assertThat(recoveredAborted.canonicalBytes()).containsExactly(aborted.canonicalBytes());
    assertThat(recoveredAborted.ownerProof().positiveDurableAbort()).isTrue();
  }

  @Test
  void rejectsUnknownMalformedPendingAndChangedFullProtectionEchoes() {
    var request = request();
    assertThatThrownBy(() -> new OriginalStartSessionAdmissionTerminalRequest(new byte[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class);
    var wireRequest = OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionAdmissionTerminalGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Closed complete");

    var committed =
        result(request, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    var validResponse = OriginalStartSessionAdmissionTerminalGrpcCodec.toResponse(committed);
    assertThatThrownBy(
            () ->
                OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(
                    request, validResponse.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Closed complete");
    assertThatThrownBy(
            () ->
                OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(
                    request,
                    validResponse.toBuilder()
                        .setCanonicalGameSessionOwnerProof(
                            ByteString.copyFrom(new byte[] {1, 2, 3}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    var changedInput =
        new AccountStartSessionAdmissionProtectionAcquisitionInput(
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest.input()
                .originalPostAuthorizationTuple(),
            UUID.fromString("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31"),
            UUID.fromString("5bc4c35d-eac4-4f9e-a8fc-7ac79aebee23"),
            21L,
            request.protectionEvidence().request().worldAdmissionHoldIdentity());
    var changedEvidence =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest.evidence(changedInput);
    var changedRequest =
        new OriginalStartSessionAdmissionTerminalRequest(changedEvidence.canonicalBytes());
    assertThatThrownBy(
            () ->
                OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(
                    request,
                    validResponse.toBuilder()
                        .setRequest(
                            OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(
                                changedRequest))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete Account protection evidence");

    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            request.protectionEvidence().request().worldAdmissionHoldIdentity(),
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
            null,
            null,
            null,
            false,
            null);
    assertThatThrownBy(() -> new OriginalStartSessionAdmissionTerminalResult(request, pending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pending");
  }

  @Test
  void rejectsWrongPointerVersionAndAnySubstitutedFullWorldHold() {
    var request = request();
    var hold = request.protectionEvidence().request().worldAdmissionHoldIdentity();
    var wrongPointer =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            2L,
            23L,
            PROOF_DIGEST,
            false,
            Instant.parse("2026-10-09T10:20:30Z"));
    assertThatThrownBy(() -> new OriginalStartSessionAdmissionTerminalResult(request, wrongPointer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact next pointer version");

    var changedHold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            hold.request(), hold.holdId(), OTHER_HOLD_FENCE);
    var substitutedHold =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            changedHold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            null,
            null,
            PROOF_DIGEST,
            true,
            Instant.parse("2026-10-09T10:20:30Z"));
    assertThatThrownBy(
            () -> new OriginalStartSessionAdmissionTerminalResult(request, substitutedHold))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account World hold identity");
  }

  private static OriginalStartSessionAdmissionTerminalRequest request() {
    var evidence =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest.evidence(
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest.input());
    return new OriginalStartSessionAdmissionTerminalRequest(evidence.canonicalBytes());
  }

  private static OriginalStartSessionAdmissionTerminalResult result(
      OriginalStartSessionAdmissionTerminalRequest request,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
    var proof =
        switch (outcome) {
          case COMMITTED ->
              new GameSessionCanonicalInitialAdmissionOwnerProof(
                  request.protectionEvidence().request().worldAdmissionHoldIdentity(),
                  outcome,
                  1L,
                  23L,
                  PROOF_DIGEST,
                  false,
                  Instant.parse("2026-10-09T10:20:30Z"));
          case ABORTED ->
              new GameSessionCanonicalInitialAdmissionOwnerProof(
                  request.protectionEvidence().request().worldAdmissionHoldIdentity(),
                  outcome,
                  null,
                  null,
                  PROOF_DIGEST,
                  true,
                  Instant.parse("2026-10-09T10:20:30Z"));
          case PENDING -> throw new IllegalArgumentException("Pending is not terminal");
        };
    return new OriginalStartSessionAdmissionTerminalResult(request, proof);
  }
}
