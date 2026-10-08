package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import org.junit.jupiter.api.Test;

class GameSessionCanonicalInitialAdmissionOwnerProofCodecTest {
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
  void literalClosedHoldVectorsRemainBoundToDistinctRequestAndWorldDigests() {
    var identity = holdIdentity(InitialAdmissionOrigin.EXPECT_CLOSED, 13L);

    assertThat(identity.request().initialAdmissionRequestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(identity.holdBindingDigest())
        .isEqualTo("sha256:6905884b086d38cb14fc9886b1b7e8b125358d9c33dc23f0300c3c387a022b72");
    assertThat(identity.holdBindingDigest()).isNotEqualTo("sha256:" + REQUEST_DIGEST);
  }

  @Test
  void committedPendingAndAbortedResultsRoundTripAsDistinctClosedOutcomes() {
    var identity = holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity, Outcome.PENDING, null, null, null, false, null);
    var committed =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity, Outcome.COMMITTED, 14L, 55L, "sha256:" + "b".repeat(64), false, TERMINAL_AT);
    var aborted =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity, Outcome.ABORTED, null, null, "sha256:" + "c".repeat(64), true, TERMINAL_AT);

    for (var proof :
        new GameSessionCanonicalInitialAdmissionOwnerProof[] {pending, committed, aborted}) {
      byte[] bytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof);
      assertThat(GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(bytes))
          .isEqualTo(proof);
    }
    assertThat(GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(committed))
        .isNotEqualTo(GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(aborted));
  }

  @Test
  void everyTerminalBindingAndResultChangeChangesCanonicalOwnerProofBytes() {
    var identity = holdIdentity(InitialAdmissionOrigin.EXPECT_CLOSED, 13L);
    var base =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity, Outcome.COMMITTED, 14L, 55L, "sha256:" + "b".repeat(64), false, TERMINAL_AT);
    byte[] bytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(base);

    assertThat(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    holdIdentity(InitialAdmissionOrigin.EXPECT_CLOSED, 12L),
                    Outcome.COMMITTED,
                    14L,
                    55L,
                    "sha256:" + "b".repeat(64),
                    false,
                    TERMINAL_AT)))
        .isNotEqualTo(bytes);
    assertThat(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    identity,
                    Outcome.COMMITTED,
                    15L,
                    55L,
                    "sha256:" + "b".repeat(64),
                    false,
                    TERMINAL_AT)))
        .isNotEqualTo(bytes);
    assertThat(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    identity,
                    Outcome.COMMITTED,
                    14L,
                    56L,
                    "sha256:" + "b".repeat(64),
                    false,
                    TERMINAL_AT)))
        .isNotEqualTo(bytes);
    assertThat(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    identity,
                    Outcome.COMMITTED,
                    14L,
                    55L,
                    "sha256:" + "d".repeat(64),
                    false,
                    TERMINAL_AT)))
        .isNotEqualTo(bytes);
  }

  @Test
  void rejectsAmbiguousOrContradictoryTerminalFields() {
    var identity = holdIdentity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);

    assertThatThrownBy(
            () ->
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    identity, Outcome.PENDING, null, null, "sha256:" + "a".repeat(64), false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    identity,
                    Outcome.ABORTED,
                    14L,
                    null,
                    "sha256:" + "a".repeat(64),
                    true,
                    TERMINAL_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    identity,
                    Outcome.ABORTED,
                    null,
                    null,
                    "sha256:" + "a".repeat(64),
                    false,
                    TERMINAL_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static HoldIdentity holdIdentity(
      InitialAdmissionOrigin origin, Long priorPointerVersion) {
    var request =
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

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
