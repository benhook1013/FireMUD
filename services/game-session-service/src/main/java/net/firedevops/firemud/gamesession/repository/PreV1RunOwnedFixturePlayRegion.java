package net.firedevops.firemud.gamesession.repository;

import io.grpc.Deadline;
import io.lettuce.core.RedisURI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRegionRuntimeStatusRepository.Source;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manual pre-v1 run-owned fixture region composition; it grants no authority to other targets. */
final class PreV1RunOwnedFixturePlayRegion {
  private static final Duration REGION_LEASE_TTL = Duration.ofSeconds(30);

  private final DSLContext dsl;
  private final PreV1CanonicalPlayStandIns standIns;
  private final RedisURI coordinationUri;
  private final CanonicalGameInstanceLaunchAssociationRepository launches;
  private final WorldCanonicalInstanceLifecycleClient world;
  private final RuntimeIdentity runtimeIdentity;
  private final Clock clock;

  PreV1RunOwnedFixturePlayRegion(
      DSLContext dsl,
      PreV1CanonicalPlayStandIns standIns,
      RedisURI coordinationUri,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      WorldCanonicalInstanceLifecycleClient world,
      RuntimeIdentity runtimeIdentity,
      Clock clock) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.standIns = Objects.requireNonNull(standIns, "standIns");
    this.coordinationUri = Objects.requireNonNull(coordinationUri, "coordinationUri");
    this.launches = Objects.requireNonNull(launches, "launches");
    this.world = Objects.requireNonNull(world, "world");
    this.runtimeIdentity = Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Prepared prepare(CanonicalPlayableTarget target, UUID accountId) {
    Objects.requireNonNull(target, "target");
    requireNonNil(accountId, "accountId");
    requireNoAmbientTransaction();

    CanonicalInitialAdmissionLaunchTarget launch = readCurrentLaunch(target);
    if (!launch.association().isRunOwnedFixture()) throw unavailable();

    CanonicalRealmCatalogSnapshot catalog =
        new GameSessionCanonicalRealmCatalogRepository(dsl)
            .readByRealm(target.targetNamespace(), target.canonicalTenantId(), target.realmId())
            .orElseThrow(PreV1RunOwnedFixturePlayRegion::unavailable);
    String frozenPolicyDigest = fixturePolicyDigest(target, launch.association(), catalog);
    PreseededActorAssignmentExpectedTarget expectedTarget =
        expectedTarget(target, launch.association(), frozenPolicyDigest);
    PreV1RunOwnedFixtureActorSource.Identity actor =
        PreV1RunOwnedFixtureActorSource.identity(accountId, expectedTarget);
    CanonicalInitialPlayerLocationRequest request =
        worldRequest(target, accountId, actor, expectedTarget);
    var lifecycleSelector = lifecycleRequest(target, launch.association(), request);
    var startRoom = world.readRunOwnedFixtureStartRoom(lifecycleSelector);
    requireCurrentLifecycle(target, launch, lifecycleSelector, startRoom.lifecycle());
    if (!launch.equals(readCurrentLaunch(target))) throw unavailable();

    PreV1RunOwnedFixturePlacementSource placement =
        standIns.fixturePlacement(
            (worldRequest, operationDeadline) -> {
              var lifecycle =
                  readCurrentFixtureLifecycle(target, launch, worldRequest, operationDeadline);
              if (!startRoom.lifecycle().equals(lifecycle)) throw unavailable();
              return lifecycle;
            },
            startRoom.roomKey());
    var resolved = placement.resolve(request, null);
    var assignment = resolved.lifecycleAssignment();
    if (!assignment.runOwnedFixture()
        || !assignment.request().equals(lifecycleRequest(target, launch.association(), request))
        || !assignment.lifecycleStatus().equals("ACTIVE")
        || assignment.lifecycleEpoch() != target.activeWorldEpoch()) {
      throw unavailable();
    }

    Source source =
        new Source(
            target.canonicalTenantId(),
            target.canonicalGameInstanceId(),
            assignment.canonicalRegionId(),
            assignment.operationalRegionId(),
            assignment.lifecycleEpoch(),
            assignment.lifecycleRowVersion(),
            assignment.request(),
            assignment.captureId(),
            assignment.graphSha256(),
            assignment.preparationInputDigest());
    CanonicalGameplayRegionRuntimeStatusRepository statusRepository =
        new CanonicalGameplayRegionRuntimeStatusRepository(dsl);
    var existing = statusRepository.find(source).orElse(null);
    if (existing != null && !existing.runOwnedFixture()) throw unavailable();
    long expectedRegionEpoch = existing == null ? 1L : existing.regionEpoch();
    if (expectedRegionEpoch <= 0L) throw unavailable();

    CanonicalGameplayRegionBindingBridgeOwner bridge =
        CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(
            dsl,
            standIns.coordination(),
            coordinationUri,
            standIns.scripts(),
            standIns.regionIncarnation(),
            placement,
            standIns.fixtureActor(),
            runtimeIdentity,
            REGION_LEASE_TTL,
            clock);
    return new Prepared(
        actor.characterId(),
        assignment.operationalRegionId(),
        expectedRegionEpoch,
        request,
        startRoom.roomKey(),
        bridge);
  }

  private CanonicalInitialAdmissionLaunchTarget readCurrentLaunch(CanonicalPlayableTarget target) {
    requireNoAmbientTransaction();
    CanonicalInitialAdmissionLaunchTarget launch =
        launches
            .readForInitialAdmission(
                target.targetNamespace(),
                target.canonicalTenantId(),
                target.realmId(),
                target.canonicalGameInstanceId())
            .orElseThrow(PreV1RunOwnedFixturePlayRegion::unavailable);
    var association = launch.association();
    if (!target.targetNamespace().equals(association.targetNamespace())
        || !target.canonicalTenantId().equals(association.canonicalTenantId())
        || !target.realmId().equals(launch.realmId())
        || !target.worldSlug().equals(association.worldSlug())
        || !target.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !target.playableStateNamespaceId().equals(association.playableStateNamespaceId())
        || !target.playableStateScope().equals(association.playableStateScope().name())
        || !association.publicProduction()
        || association.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING
        || !target.canonicalVersionId().equals(launch.canonicalVersionId())
        || target.gameInstanceId() != launch.gameInstanceId()
        || target.runtimeVersionId() != launch.runtimeVersionId()
        || target.gameSessionTenantId() != association.gameSessionTenantId()) {
      throw unavailable();
    }
    return launch;
  }

  private WorldRunOwnedFixtureLifecycleEvidence readCurrentFixtureLifecycle(
      CanonicalPlayableTarget target,
      CanonicalInitialAdmissionLaunchTarget expectedLaunch,
      CanonicalInitialPlayerLocationRequest request,
      Deadline operationDeadline) {
    requireNoAmbientTransaction();
    CanonicalInitialAdmissionLaunchTarget before = readCurrentLaunch(target);
    if (!expectedLaunch.equals(before) || !before.association().isRunOwnedFixture()) {
      throw unavailable();
    }
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        lifecycleRequest(target, before.association(), request);
    WorldRunOwnedFixtureLifecycleEvidence lifecycle =
        world.readRunOwnedFixture(lifecycleRequest, operationDeadline);
    requireCurrentLifecycle(target, before, lifecycleRequest, lifecycle);
    CanonicalInitialAdmissionLaunchTarget after = readCurrentLaunch(target);
    if (!before.equals(after)) throw unavailable();
    return lifecycle;
  }

  private static void requireCurrentLifecycle(
      CanonicalPlayableTarget target,
      CanonicalInitialAdmissionLaunchTarget launch,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      WorldRunOwnedFixtureLifecycleEvidence lifecycle) {
    if (lifecycle == null
        || !request.equals(lifecycle.request())
        || !"ACTIVE".equals(lifecycle.lifecycleStatus())
        || lifecycle.lifecycleEpoch() != target.activeWorldEpoch()
        || !launch
            .association()
            .fixtureProvenanceDigest()
            .equals(lifecycle.fixtureProvenanceDigest())) {
      throw unavailable();
    }
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      CanonicalPlayableTarget target,
      CanonicalGameInstanceLaunchAssociation association,
      CanonicalInitialPlayerLocationRequest request) {
    if (!association.isRunOwnedFixture()) throw unavailable();
    UUID placementOperationId;
    try {
      placementOperationId = UUID.fromString(request.getPlacementOperationUuid());
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
    if (!placementOperationId.toString().equals(request.getPlacementOperationUuid())) {
      throw unavailable();
    }
    var descriptor = association.descriptor();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        WorldCanonicalInstanceLifecycleEvidence.initialLocationReadRequestId(placementOperationId),
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.canonicalGameInstanceId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        true,
        association.controlPlaneRequestId(),
        target.canonicalVersionId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        association.attestationDigest());
  }

  private static CanonicalInitialPlayerLocationRequest worldRequest(
      CanonicalPlayableTarget target,
      UUID accountId,
      PreV1RunOwnedFixtureActorSource.Identity actor,
      PreseededActorAssignmentExpectedTarget expectedTarget) {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(UUID.randomUUID().toString())
        .setExpectedTarget(expectedTarget)
        .setCanonicalAccountUuid(accountId.toString())
        .setCharacterUuid(actor.characterId().toString())
        .setAssignmentUuid(actor.assignmentId().toString())
        .setAssignmentIntentDigest(actor.intentDigest())
        .setInitialAdmissionHoldUuid(target.holdId().toString())
        .setInitialAdmissionHoldFence(target.holdFence().toString())
        .setInitialAdmissionRequestId(target.initialAdmissionRequestId())
        .setInitialAdmissionRequestDigest(target.initialAdmissionRequestDigest())
        .setActiveLifecycleEpoch(target.activeWorldEpoch())
        .setTerminalProofDigest(target.ownerProofDigest())
        .setCommittedPointerVersion(target.pointerVersion())
        .setAuditEventId(target.auditEventId())
        .build();
  }

  private static PreseededActorAssignmentExpectedTarget expectedTarget(
      CanonicalPlayableTarget target,
      CanonicalGameInstanceLaunchAssociation association,
      String frozenPolicyDigest) {
    if (!"SHARED".equals(target.playableStateScope())) throw unavailable();
    var descriptor = association.descriptor();
    return PreseededActorAssignmentExpectedTarget.newBuilder()
        .setCanonicalTenantUuid(target.canonicalTenantId().toString())
        .setRealmUuid(target.realmId().toString())
        .setWorldSlug(target.worldSlug())
        .setRealmSlug(target.realmSlug())
        .setGameInstanceId(target.canonicalGameInstanceId().toString())
        .setCatalogRevision(target.catalogRevision())
        .setCanonicalVersionUuid(target.canonicalVersionId().toString())
        .setFrozenPolicyDigest(frozenPolicyDigest)
        .setPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef())
        .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .build();
  }

  private static String fixturePolicyDigest(
      CanonicalPlayableTarget target,
      CanonicalGameInstanceLaunchAssociation association,
      CanonicalRealmCatalogSnapshot catalog) {
    CanonicalRealmCatalogSnapshot.FixturePolicy policy = catalog.fixturePolicy();
    UUID operationId;
    try {
      operationId = UUID.fromString(association.controlPlaneRequestId());
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
    if (!catalog.isRunOwnedFixture()
        || policy == null
        || !target.targetNamespace().equals(catalog.targetNamespace())
        || !target.canonicalTenantId().equals(catalog.tenantId())
        || !target.worldSlug().equals(catalog.worldSlug())
        || !target.realmId().equals(catalog.realmId())
        || !target.realmSlug().equals(catalog.realmSlug())
        || !target.playableStateNamespaceId().equals(catalog.playableStateNamespaceId())
        || !target.playableStateScope().equals(catalog.stateScope())
        || !target.characterCreationPolicy().equals(catalog.characterCreationPolicy())
        || target.catalogRevision() != catalog.catalogRevision()
        || !operationId.toString().equals(association.controlPlaneRequestId())
        || !operationId.equals(policy.sealOperationId())
        || !association.fixtureProvenanceDigest().equals(policy.sealEvidenceDigest())
        || !RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                policy.sealOperationId(), policy.sealRequestDigest())
            .equals(association.fixtureProvenanceDigest())
        || !policy.sealRequestDigest().matches("sha256:[0-9a-f]{64}")) {
      throw unavailable();
    }
    return policy.sealRequestDigest().substring("sha256:".length());
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static void requireNonNil(UUID value, String field) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Run-owned fixture region preparation is unavailable");
  }

  record Prepared(
      UUID characterId,
      UUID operationalRegionId,
      long regionEpoch,
      CanonicalInitialPlayerLocationRequest request,
      String roomKey,
      CanonicalGameplayRegionBindingBridgeOwner bridge) {
    Prepared {
      requireNonNil(characterId, "characterId");
      requireNonNil(operationalRegionId, "operationalRegionId");
      if (regionEpoch <= 0L) throw new IllegalArgumentException("regionEpoch must be positive");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(roomKey, "roomKey");
      Objects.requireNonNull(bridge, "bridge");
    }
  }
}
