package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import org.junit.jupiter.api.Test;

class CanonicalInitialAdmissionOwnerGrpcCodecTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final Instant TERMINAL_AT = Instant.parse("2025-02-03T04:05:06.123456Z");

  @Test
  void requestRoundTripsTheCompleteHoldTupleAndOptionalOriginShape() {
    for (HoldIdentity identity :
        List.of(
            holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null),
            holdIdentity(InitialAdmissionOrigin.EXPECT_CLOSED, 13L))) {
      var request = CanonicalInitialAdmissionOwnerGrpcCodec.toRequest(identity);

      assertThat(CanonicalInitialAdmissionOwnerGrpcCodec.parseRequest(request)).isEqualTo(identity);
      assertThat(request.hasExpectedPriorPointerVersion())
          .isEqualTo(identity.request().expectedPriorPointerVersion() != null);
      assertThat(request.getHoldBindingDigest()).isEqualTo(identity.holdBindingDigest());
    }
  }

  @Test
  void responseRoundTripsPendingCommittedAndAbortedOwnerOutcomes() {
    HoldIdentity noPrior = holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    HoldIdentity closed = holdIdentity(InitialAdmissionOrigin.EXPECT_CLOSED, 13L);
    List<GameSessionCanonicalInitialAdmissionOwnerProof> proofs =
        List.of(
            proof(noPrior, Outcome.PENDING),
            proof(noPrior, Outcome.COMMITTED),
            proof(closed, Outcome.COMMITTED),
            proof(closed, Outcome.ABORTED));

    for (GameSessionCanonicalInitialAdmissionOwnerProof proof : proofs) {
      var response =
          CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(proof.holdIdentity(), proof);
      assertThat(
              CanonicalInitialAdmissionOwnerGrpcCodec.requireResponse(
                  proof.holdIdentity(), response))
          .isEqualTo(proof);
    }
  }

  @Test
  void rejectsEveryChangedEchoedTupleFieldAndOptionalPriorVersionPresence() {
    HoldIdentity identity = holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    GetCanonicalInitialAdmissionOwnerProofResponse exact =
        CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(
            identity, proof(identity, Outcome.PENDING));
    List<UnaryOperator<GetCanonicalInitialAdmissionOwnerProofResponse>> changes =
        List.of(
            value -> value.toBuilder().setTargetNamespace("other").build(),
            value -> value.toBuilder().setInitialAdmissionRequestId("other").build(),
            value -> value.toBuilder().setInitialAdmissionRequestDigest("b".repeat(64)).build(),
            value -> value.toBuilder().setCanonicalTenantId(uuid(61).toString()).build(),
            value -> value.toBuilder().setWorldSlug("other-world").build(),
            value -> value.toBuilder().setRealmId(uuid(62).toString()).build(),
            value -> value.toBuilder().setPlayableStateNamespaceId(uuid(63).toString()).build(),
            value -> value.toBuilder().setPlayableStateScope("ISOLATED").build(),
            value -> value.toBuilder().setCanonicalGameInstanceId(uuid(64).toString()).build(),
            value -> value.toBuilder().setCanonicalVersionId(uuid(65).toString()).build(),
            value -> value.toBuilder().setActiveLifecycleEpoch(8L).build(),
            value -> value.toBuilder().setExpectedCatalogRevision(13L).build(),
            value ->
                value.toBuilder()
                    .setOrigin(
                        CanonicalInitialAdmissionOrigin
                            .CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED)
                    .build(),
            value -> value.toBuilder().setExpectedPriorPointerVersion(1L).build(),
            value -> value.toBuilder().setHoldId(uuid(66).toString()).build(),
            value -> value.toBuilder().setHoldFence(uuid(67).toString()).build(),
            value -> value.toBuilder().setHoldBindingDigest("sha256:" + "b".repeat(64)).build());

    for (UnaryOperator<GetCanonicalInitialAdmissionOwnerProofResponse> change : changes) {
      assertRejected(identity, change.apply(exact));
    }
    HoldIdentity prior = holdIdentity(InitialAdmissionOrigin.EXPECT_CLOSED, 13L);
    GetCanonicalInitialAdmissionOwnerProofResponse exactWithPrior =
        CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(prior, proof(prior, Outcome.PENDING));
    assertRejected(prior, exactWithPrior.toBuilder().clearExpectedPriorPointerVersion().build());
  }

  @Test
  void rejectsUnknownFieldsUnsupportedOutcomesAndUnexpectedPendingTerminalData() {
    HoldIdentity identity = holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var pending =
        CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(
            identity, proof(identity, Outcome.PENDING));
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertRejected(identity, pending.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        identity,
        pending.toBuilder()
            .setOutcome(
                CanonicalInitialAdmissionOwnerProofOutcome
                    .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_UNSPECIFIED)
            .build());
    assertRejected(identity, pending.toBuilder().setOutcomeValue(99).build());
    assertRejected(identity, pending.toBuilder().setProofDigest("unexpected").build());
    assertRejected(
        identity, pending.toBuilder().setTerminalAt(Timestamp.getDefaultInstance()).build());
  }

  @Test
  void rejectsInvalidTerminalProofShapesAndNoncanonicalTimestamp() {
    HoldIdentity identity = holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    GetCanonicalInitialAdmissionOwnerProofResponse committed =
        CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(
            identity, proof(identity, Outcome.COMMITTED));

    assertRejected(identity, committed.toBuilder().setCommittedPointerVersion(2L).build());
    assertRejected(identity, committed.toBuilder().clearCommittedPointerVersion().build());
    assertRejected(identity, committed.toBuilder().clearAuditEventId().build());
    assertRejected(identity, committed.toBuilder().clearTerminalAt().build());
    GetCanonicalInitialAdmissionOwnerProofResponse aborted =
        CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(
            identity, proof(identity, Outcome.ABORTED));
    assertRejected(identity, aborted.toBuilder().clearTerminalAt().build());
    assertRejected(
        identity,
        committed.toBuilder()
            .setTerminalAt(committed.getTerminalAt().toBuilder().setNanos(1_000_000_000).build())
            .build());
    assertRejected(
        identity,
        committed.toBuilder()
            .setTerminalAt(
                committed.getTerminalAt().toBuilder().setSeconds(253_402_300_800L).build())
            .build());
    assertRejected(
        identity,
        committed.toBuilder()
            .setTerminalAt(
                committed.getTerminalAt().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(101, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build());
  }

  private static void assertRejected(
      HoldIdentity identity, GetCanonicalInitialAdmissionOwnerProofResponse response) {
    assertThatThrownBy(
            () -> CanonicalInitialAdmissionOwnerGrpcCodec.requireResponse(identity, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof proof(
      HoldIdentity identity, Outcome outcome) {
    return switch (outcome) {
      case PENDING ->
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              identity, outcome, null, null, null, false, null);
      case COMMITTED ->
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              identity,
              outcome,
              identity.request().initialAdmissionOrigin() == InitialAdmissionOrigin.NO_PRIOR_POINTER
                  ? 1L
                  : identity.request().expectedPriorPointerVersion() + 1L,
              55L,
              "sha256:" + "b".repeat(64),
              false,
              TERMINAL_AT);
      case ABORTED ->
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              identity, outcome, null, null, "sha256:" + "c".repeat(64), true, TERMINAL_AT);
    };
  }

  private static HoldIdentity holdIdentity(
      InitialAdmissionOrigin origin, Long priorPointerVersion) {
    Request request =
        new Request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            origin,
            12L,
            priorPointerVersion);
    return new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", value));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
