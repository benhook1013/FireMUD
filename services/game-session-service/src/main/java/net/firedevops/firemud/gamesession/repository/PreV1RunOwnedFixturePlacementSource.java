package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.gamesession.service.CanonicalRegionBootstrapAssignmentResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationLifecycleConsequence;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;

/**
 * Pre-v1 gate waiver (ADR 0185): the run-owned fixture's initial placement, standing in for World's
 * initial-placement owner. It joins World's current ACTIVE fixture lifecycle (read fresh on every
 * call through {@link FixtureLifecycleReader}) to the World-owned fixture start room. The fixture
 * has one region per game instance; its canonical and operational ids derive from the game instance
 * and start room. World places no fixture actor; a real placement is owed before v1.
 */
final class PreV1RunOwnedFixturePlacementSource
    implements CanonicalGameplayRegionLeaseOwner.PlacementSource {
  static final UUID NAMESPACE = UUID.fromString("d900cc0a-6282-4e8f-9888-2163bc1591f1");

  /** Fresh authenticated World fixture lifecycle read for one exact World request's target. */
  @FunctionalInterface
  interface FixtureLifecycleReader {
    WorldRunOwnedFixtureLifecycleEvidence read(
        CanonicalInitialPlayerLocationRequest request, Deadline operationDeadline);
  }

  private final PreV1GateWaivers waivers;
  private final FixtureLifecycleReader lifecycleReader;
  private final String startRoomKey;

  PreV1RunOwnedFixturePlacementSource(
      PreV1GateWaivers waivers, FixtureLifecycleReader lifecycleReader, String startRoomKey) {
    if (waivers == null || !waivers.enabled()) {
      throw new IllegalStateException("Run-owned fixture placement stand-in requires the waivers");
    }
    if (startRoomKey == null
        || startRoomKey.isBlank()
        || !startRoomKey.equals(startRoomKey.trim())) {
      throw new IllegalArgumentException("World-owned fixture start room required");
    }
    this.waivers = waivers;
    this.lifecycleReader = Objects.requireNonNull(lifecycleReader, "lifecycleReader");
    this.startRoomKey = startRoomKey;
  }

  /** The fixture's single canonical region for one game instance. */
  UUID canonicalRegionId(UUID gameInstanceId) {
    return PreV1RunOwnedFixtureActorSource.uuidV5(
        NAMESPACE, "canonical-region|" + gameInstanceId + "|" + startRoomKey);
  }

  /** The operational region the PLAY composition binds its candidate to. */
  UUID operationalRegionId(UUID tenantId, UUID gameInstanceId) {
    return PreV1RunOwnedFixtureActorSource.uuidV5(
        NAMESPACE, "operational-region|" + tenantId + "|" + gameInstanceId + "|" + startRoomKey);
  }

  String startRoomKey() {
    return startRoomKey;
  }

  @Override
  public CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolve(
      CanonicalInitialPlayerLocationRequest request, Deadline operationDeadline) {
    Objects.requireNonNull(request, "request");
    if (request.getSchemaVersion() != 1
        || request.hasPlayerCreatedOrigin()
        || !request.hasExpectedTarget()) {
      throw unavailable();
    }
    var target = request.getExpectedTarget();
    final UUID tenantId;
    final UUID gameInstanceId;
    final UUID versionId;
    try {
      tenantId = UUID.fromString(target.getCanonicalTenantUuid());
      gameInstanceId = UUID.fromString(target.getGameInstanceId());
      versionId = UUID.fromString(target.getCanonicalVersionUuid());
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
    WorldRunOwnedFixtureLifecycleEvidence lifecycle =
        lifecycleReader.read(request, operationDeadline);
    if (lifecycle == null
        || !"ACTIVE".equals(lifecycle.lifecycleStatus())
        || lifecycle.lifecycleEpoch() != request.getActiveLifecycleEpoch()
        || !tenantId.equals(lifecycle.request().canonicalTenantId())
        || !gameInstanceId.equals(lifecycle.request().canonicalGameInstanceId())
        || !versionId.equals(lifecycle.request().canonicalVersionId())
        || !target.getWorldSlug().equals(lifecycle.request().worldSlug())) {
      throw unavailable();
    }
    UUID canonicalRegionId = canonicalRegionId(gameInstanceId);
    UUID operationalRegionId = operationalRegionId(tenantId, gameInstanceId);
    var assignment =
        CanonicalRegionBootstrapAssignmentResolver.Assignment.preV1RunOwnedFixture(
            waivers, lifecycle, canonicalRegionId, operationalRegionId);
    var placement =
        CanonicalInitialPlayerLocationResponse.newBuilder()
            .setSchemaVersion(1)
            .setPlacementOperationUuid(request.getPlacementOperationUuid())
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setCharacterUuid(request.getCharacterUuid())
            .setAssignmentUuid(request.getAssignmentUuid())
            .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
            .setOutcome(
                CanonicalInitialPlayerLocationOutcome
                    .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
            .setPrivateRuntimeRoomKey(startRoomKey)
            .build();
    var observation =
        CanonicalInitialPlayerLocationRegionResponse.newBuilder()
            .setSchemaVersion(1)
            .setPlacementResult(placement)
            .setExpectedTarget(target)
            .setCanonicalRegionInstanceUuid(canonicalRegionId.toString())
            .setOperationalRegionUuid(operationalRegionId.toString())
            .setLifecycle(
                CanonicalInitialPlayerLocationLifecycleConsequence.newBuilder()
                    .setCanonicalRequestBytes(
                        ByteString.copyFrom(lifecycle.request().canonicalBytes()))
                    .setLifecycleStatus(lifecycle.lifecycleStatus())
                    .setLifecycleEpoch(lifecycle.lifecycleEpoch())
                    .setRowVersion(lifecycle.rowVersion())
                    .setPreparationInputDigest(lifecycle.preparationInputDigest())
                    .build())
            .build();
    return CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion.preV1RunOwnedFixture(
        observation, assignment);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Run-owned fixture placement requires its exact current ACTIVE fixture lifecycle");
  }
}
