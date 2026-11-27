package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.junit.jupiter.api.Test;

/** Controlled content only: the stand-ins grant no World placement or Entity provenance. */
class PreV1RunOwnedFixtureStandInsTest {
  private static final String NAMESPACE = "test";
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID GAME = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID STATE = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID REALM = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String PROVENANCE = "sha256:" + "b".repeat(64);
  private static final String START_ROOM = "R-1021";

  @Test
  void actorIdentityIsDeterministicPerAccountAndRefusesAnyOtherRequest() {
    var identity = PreV1RunOwnedFixtureActorSource.identity(ACCOUNT, target());
    assertThat(identity.characterId())
        .isEqualTo(PreV1RunOwnedFixtureActorSource.characterId(ACCOUNT))
        .isEqualTo(
            PreV1RunOwnedFixtureActorSource.uuidV5(
                PreV1RunOwnedFixtureActorSource.NAMESPACE, ACCOUNT.toString()));
    assertThat(identity.characterId().version()).isEqualTo(5);
    assertThat(PreV1RunOwnedFixtureActorSource.identity(ACCOUNT, target())).isEqualTo(identity);
    assertThat(PreV1RunOwnedFixtureActorSource.characterId(UUID.randomUUID()))
        .isNotEqualTo(identity.characterId());
    assertThat(identity.runtimeEntityId()).isPositive().isLessThanOrEqualTo((1L << 53) - 1L);

    var source = new PreV1RunOwnedFixtureActorSource(PreV1GateWaivers.enabledForTest(), NAMESPACE);
    var request = request(1L);
    CanonicalGameplayRuntimeActorReference first = source.read(request);
    CanonicalGameplayRuntimeActorReference again = source.read(request);
    assertThat(first.matchesWorldRequest(request)).isTrue();
    assertThat(first.characterUuid()).isEqualTo(identity.characterId());
    assertThat(first.canonicalAccountUuid()).isEqualTo(ACCOUNT);
    assertThat(first.runtimeEntityId().value()).isEqualTo(identity.runtimeEntityId());
    assertThat(first.workloadNamespace()).isEqualTo(NAMESPACE);
    assertThat(first.origin())
        .isEqualTo(
            new CanonicalGameplayRuntimeActorReference.AssignmentOrigin(
                identity.assignmentId(), identity.intentDigest()));
    assertThat(CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(first))
        .isEqualTo(CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(again));

    var otherCharacter = request.toBuilder().setCharacterUuid(UUID.randomUUID().toString()).build();
    var otherIntent = request.toBuilder().setAssignmentIntentDigest("c".repeat(64)).build();
    for (var invalid : java.util.List.of(otherCharacter, otherIntent)) {
      assertThatThrownBy(() -> source.read(invalid)).isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () -> new PreV1RunOwnedFixtureActorSource(PreV1GateWaivers.disabled(), NAMESPACE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void placementJoinsTheActiveFixtureLifecycleToTheLegacyStartRoom() {
    var lifecycle = lifecycle("ACTIVE", 1L);
    var placement =
        new PreV1RunOwnedFixturePlacementSource(
            PreV1GateWaivers.enabledForTest(), (request, deadline) -> lifecycle, START_ROOM);
    var resolved = placement.resolve(request(1L), null);
    var assignment = resolved.lifecycleAssignment();

    assertThat(assignment.runOwnedFixture()).isTrue();
    assertThat(assignment.captureId()).isNull();
    assertThat(assignment.graphSha256()).isNull();
    assertThat(assignment.request()).isEqualTo(lifecycle.request());
    assertThat(assignment.preparationInputDigest()).isEqualTo(lifecycle.preparationInputDigest());
    assertThat(resolved.operationalRegionId())
        .isEqualTo(placement.operationalRegionId(TENANT, GAME));
    assertThat(resolved.canonicalRegionInstanceId()).isEqualTo(placement.canonicalRegionId(GAME));
    assertThat(resolved.observation().getPlacementResult().getPrivateRuntimeRoomKey())
        .isEqualTo(START_ROOM);
    assertThat(resolved.observation().getLifecycle().getCaptureUuid()).isEmpty();
    assertThat(CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(request(1L), resolved))
        .matches("[0-9a-f]{64}");

    for (var stale : java.util.List.of(lifecycle("TERMINATING", 1L), lifecycle("ACTIVE", 2L))) {
      var refusing =
          new PreV1RunOwnedFixturePlacementSource(
              PreV1GateWaivers.enabledForTest(), (request, deadline) -> stale, START_ROOM);
      assertThatThrownBy(() -> refusing.resolve(request(1L), null))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                new PreV1RunOwnedFixturePlacementSource(
                    PreV1GateWaivers.disabled(), (request, deadline) -> lifecycle, START_ROOM))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fixtureRegionSourceAndStatusCarryNoCaptureOrGraph() {
    var lifecycleRequest = lifecycle("ACTIVE", 1L).request();
    UUID region = UUID.randomUUID();
    var fixture =
        new CanonicalGameplayRegionRuntimeStatusRepository.Source(
            TENANT,
            GAME,
            UUID.randomUUID(),
            region,
            1L,
            0L,
            lifecycleRequest,
            null,
            null,
            "sha256:" + "d".repeat(64));
    assertThat(fixture.runOwnedFixture()).isTrue();
    assertThat(fixture.worldSourceKind()).isEqualTo("RUN_OWNED_FIXTURE");
    assertThatThrownBy(
            () ->
                new CanonicalGameplayRegionRuntimeStatusRepository.Source(
                    TENANT,
                    GAME,
                    UUID.randomUUID(),
                    region,
                    1L,
                    0L,
                    lifecycleRequest,
                    UUID.randomUUID(),
                    null,
                    "sha256:" + "d".repeat(64)))
        .isInstanceOf(IllegalStateException.class);
    var human =
        new CanonicalGameplayRegionRuntimeStatusRepository.Source(
            TENANT,
            GAME,
            UUID.randomUUID(),
            region,
            1L,
            0L,
            lifecycleRequest,
            UUID.randomUUID(),
            "e".repeat(64),
            "sha256:" + "d".repeat(64));
    assertThat(human.worldSourceKind()).isEqualTo("HUMAN_CAPTURE");

    String tag = CanonicalGameplayRegionRedisKeys.tenantRegionTag(TENANT, GAME, region);
    var status =
        new CanonicalGameplayRegionRuntimeStatus(
            TENANT,
            GAME,
            UUID.randomUUID(),
            region,
            tag,
            1L,
            0L,
            lifecycleRequest,
            null,
            null,
            "sha256:" + "d".repeat(64),
            1L,
            BigInteger.ONE,
            "f".repeat(64),
            "game-session-service",
            "instance-1",
            false,
            null,
            -1L,
            Instant.EPOCH);
    assertThat(status.runOwnedFixture()).isTrue();
  }

  private static PreseededActorAssignmentExpectedTarget target() {
    return PreseededActorAssignmentExpectedTarget.newBuilder()
        .setCanonicalTenantUuid(TENANT.toString())
        .setRealmUuid(REALM.toString())
        .setWorldSlug("fixture-world")
        .setRealmSlug("fixture-realm")
        .setGameInstanceId(GAME.toString())
        .setCatalogRevision(1L)
        .setCanonicalVersionUuid(VERSION.toString())
        .setFrozenPolicyDigest("a".repeat(64))
        .setPlayableStateNamespaceUuid(STATE.toString())
        .setPublishedReleaseBundleRef("fixture-bundle")
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .build();
  }

  private static CanonicalInitialPlayerLocationRequest request(long activeEpoch) {
    var identity = PreV1RunOwnedFixtureActorSource.identity(ACCOUNT, target());
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(UUID.randomUUID().toString())
        .setExpectedTarget(target())
        .setCanonicalAccountUuid(ACCOUNT.toString())
        .setCharacterUuid(identity.characterId().toString())
        .setAssignmentUuid(identity.assignmentId().toString())
        .setAssignmentIntentDigest(identity.intentDigest())
        .setActiveLifecycleEpoch(activeEpoch)
        .build();
  }

  private static WorldRunOwnedFixtureLifecycleEvidence lifecycle(String status, long epoch) {
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            1,
            UUID.randomUUID(),
            NAMESPACE,
            TENANT,
            "fixture-world",
            GAME,
            STATE,
            "SHARED",
            true,
            "control-plane-request",
            VERSION,
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            PROVENANCE);
    return new WorldRunOwnedFixtureLifecycleEvidence(
        request, PROVENANCE, "sha256:" + "3".repeat(64), status, epoch, 4L);
  }
}
