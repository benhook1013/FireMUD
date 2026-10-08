package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.junit.jupiter.api.Test;

/** Component comparison proof only; mocks here are not authenticated owner or database evidence. */
class WorldCanonicalPlayerLocationHoldProofTest {
  @Test
  void bothTaggedOriginsRequireTheExactTypedFirstOpenOutcome() {
    for (var origin : WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.values()) {
      var fixture =
          fixture(
              origin,
              origin == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED
                  ? 9L
                  : null);
      assertThatCode(() -> fixture.verify(fixture.proof())).doesNotThrowAnyException();
    }
  }

  @Test
  void changedRealmAndNamespaceCannotUseAnotherHold() {
    var fixture =
        fixture(WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    when(fixture.request().realmId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> fixture.verify(fixture.proof()))
        .isInstanceOf(IllegalStateException.class);
    when(fixture.request().realmId())
        .thenReturn(fixture.proof().holdIdentity().request().realmId());
    when(fixture.current().request().targetNamespace()).thenReturn("another-namespace");
    assertThatThrownBy(() -> fixture.verify(fixture.proof()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void changedProofDigestOrAuditCannotReplaceRetainedCommitEvidence() {
    var fixture =
        fixture(WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    when(fixture.request().initialAdmissionOwnerProofDigest()).thenReturn("d".repeat(64));
    assertThatThrownBy(() -> fixture.verify(fixture.proof()))
        .isInstanceOf(IllegalStateException.class);
    when(fixture.request().initialAdmissionOwnerProofDigest()).thenReturn("c".repeat(64));
    when(fixture.request().pointerAuditId()).thenReturn("100");
    assertThatThrownBy(() -> fixture.verify(fixture.proof()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void positiveAbortAndPendingNeverAuthorizeLocation() {
    var fixture =
        fixture(WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var abort =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            fixture.proof().holdIdentity(),
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            null,
            null,
            "sha256:" + "e".repeat(64),
            true,
            Instant.parse("2026-10-07T00:00:00Z"));
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            fixture.proof().holdIdentity(),
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
            null,
            null,
            null,
            false,
            null);
    assertThatThrownBy(() -> fixture.verify(abort)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> fixture.verify(pending)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void expectClosedCannotSkipPointerVersionsOrOverflow() {
    var fixture =
        fixture(WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED, 9L);
    var jumped =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            fixture.proof().holdIdentity(),
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            11L,
            99L,
            fixture.proof().proofDigest(),
            false,
            fixture.proof().terminalAt());
    when(fixture.request().pointerVersion()).thenReturn(11L);
    assertThatThrownBy(() -> fixture.verify(jumped)).isInstanceOf(IllegalStateException.class);
    var overflow =
        fixture(
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED,
            Long.MAX_VALUE);
    assertThatThrownBy(() -> overflow.verify(overflow.proof()))
        .isInstanceOf(ArithmeticException.class);
  }

  private static Fixture fixture(
      WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin origin, Long prior) {
    UUID tenant = UUID.randomUUID();
    UUID realm = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            "world-hold-test",
            tenant,
            "starter-world",
            realm,
            namespace,
            "SHARED",
            instance,
            version,
            7L,
            "initial-request",
            "b".repeat(64),
            origin,
            3L,
            prior);
    var identity =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest, UUID.randomUUID(), UUID.randomUUID());
    long pointer = prior == null ? 1L : prior == Long.MAX_VALUE ? 1L : prior + 1;
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            pointer,
            99L,
            "sha256:" + "c".repeat(64),
            false,
            Instant.parse("2026-10-07T00:00:00Z"));
    var selector = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(selector.targetNamespace()).thenReturn("world-hold-test");
    when(selector.canonicalVersionId()).thenReturn(version);
    var current = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(current.request()).thenReturn(selector);
    when(current.lifecycleEpoch()).thenReturn(7L);
    var association = mock(WorldCanonicalInstanceAssociation.class);
    when(association.identity())
        .thenReturn(
            new WorldCanonicalInstanceAssociation.CanonicalIdentity(
                instance,
                "world-hold-test",
                tenant,
                "starter-world",
                namespace,
                "SHARED",
                true,
                "launch-request"));
    var request = mock(WorldCanonicalInitialPlayerLocation.Request.class);
    when(request.initialAdmissionOrigin())
        .thenReturn(
            WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.valueOf(origin.name()));
    when(request.initialAdmissionHoldId()).thenReturn(identity.holdId());
    when(request.initialAdmissionHoldFence()).thenReturn(identity.holdFence());
    when(request.canonicalTenantId()).thenReturn(tenant);
    when(request.worldSlug()).thenReturn("starter-world");
    when(request.canonicalGameInstanceId()).thenReturn(instance);
    when(request.realmId()).thenReturn(realm);
    when(request.playableStateNamespaceId()).thenReturn(namespace);
    when(request.playableStateScope()).thenReturn("SHARED");
    when(request.initialAdmissionRequestId()).thenReturn("initial-request");
    when(request.initialAdmissionRequestDigest()).thenReturn("b".repeat(64));
    when(request.catalogRevision()).thenReturn(3L);
    when(request.initialAdmissionOwnerProofId()).thenReturn("initial-request");
    when(request.initialAdmissionOwnerProofDigest()).thenReturn("c".repeat(64));
    when(request.pointerAuditId()).thenReturn("99");
    when(request.pointerVersion()).thenReturn(pointer);
    return new Fixture(request, current, association, proof);
  }

  private record Fixture(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current,
      WorldCanonicalInstanceAssociation association,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    void verify(GameSessionCanonicalInitialAdmissionOwnerProof candidate) {
      WorldCanonicalInitialPlayerLocationRepository.requireCommittedCanonicalHold(
          request, current, association, candidate);
    }
  }
}
