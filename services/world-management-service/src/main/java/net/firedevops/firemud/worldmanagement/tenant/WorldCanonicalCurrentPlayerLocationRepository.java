package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered World-local current read of one initial actor placement. It proves no external
 * Entity, Account, or Game Session authority by itself.
 */
public final class WorldCanonicalCurrentPlayerLocationRepository {
  private final DSLContext dsl;
  private final TransactionTemplate readTransaction;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final InitialAdmissionBindHoldRepository holdRepository;
  private final WorldCanonicalInitialPlayerLocationRepository placementRepository;

  public WorldCanonicalCurrentPlayerLocationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      InitialAdmissionBindHoldRepository holdRepository,
      WorldCanonicalInitialPlayerLocationRepository placementRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository, "lifecycleRepository");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
    this.placementRepository = Objects.requireNonNull(placementRepository, "placementRepository");
    readTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);
  }

  /** Requires current owner authority even for an empty read and never opens a nested snapshot. */
  public Optional<CurrentLocation> read(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInitialPlayerLocationService.HeldPlacementAuthority authority) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(authority, "authority");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World current-location read must start outside an ambient transaction");
    }
    return readTransaction.execute(
        status -> {
          requireReadOnlyRepeatableReadTransaction();
          authority.requireHeld();

          WorldCanonicalInstanceLifecycleEvidence current =
              lifecycleRepository
                  .readForCurrentLocationInOwnerTransaction(
                      request.activeLifecycleEvidence().request())
                  .orElseThrow(
                      () -> denied("exact canonical World lifecycle association is missing"));
          if (!"ACTIVE".equals(current.lifecycleStatus())
              || !Arrays.equals(
                  request.normalizedLifecycleEvidenceBytes(),
                  WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                      current.canonicalBytes()))) {
            throw denied("current ACTIVE lifecycle or V42 ROOM evidence differs from the request");
          }

          WorldCanonicalInstanceAssociation association =
              associationRepository
                  .readOwnerAssociationInOwnerTransaction(request.canonicalGameInstanceId())
                  .orElseThrow(() -> denied("exact canonical World association is missing"));
          placementRepository.requireAssociation(request, current, association);

          InitialAdmissionBindHold hold =
              holdRepository
                  .findByHoldId(request.initialAdmissionHoldId().toString())
                  .orElseThrow(() -> denied("initial-admission hold is missing"));
          WorldCanonicalInitialPlayerLocationRepository.requireCommittedNoPriorPointerHold(
              request, current, association, hold);

          StoredOperation operation = readOperation(request, association);
          var locations = placementRepository.findExistingLocationsForCurrentRead(request);
          if (locations.isEmpty()) {
            if (operation != null
                && operation.result().outcome()
                    == WorldCanonicalInitialPlayerLocation.Outcome.APPLIED) {
              throw denied("retained APPLIED operation has no exact current location row");
            }
            authority.requireHeld();
            return Optional.empty();
          }
          if (locations.size() != 1) {
            throw denied("actor and Entity assignment resolve to different World locations");
          }
          Record location = locations.getFirst();
          if (!WorldCanonicalInitialPlayerLocationRepository.sameInitialPlacement(
                  request, current, location)
              || !request
                  .canonicalGameInstanceId()
                  .equals(location.get("canonical_game_instance_id", UUID.class))
              || association.worldInstanceId() != location.get("world_instance_id", Long.class)
              || !request
                  .operationId()
                  .equals(location.get("initial_location_operation_id", UUID.class))
              || !request
                  .requestDigest()
                  .equals(location.get("initial_location_request_digest", String.class))) {
            throw denied(
                "current World location differs from the exact actor or assignment binding");
          }

          if (operation == null
              || operation.result().outcome()
                  != WorldCanonicalInitialPlayerLocation.Outcome.APPLIED) {
            throw denied("current World location has no matching APPLIED operation result");
          }
          WorldCanonicalInitialPlayerLocation.Result result = operation.result();
          if (result.outcome() != WorldCanonicalInitialPlayerLocation.Outcome.APPLIED
              || !result.startLocation().equals(current.startLocation())
              || !Objects.equals(result.runtimeRoomInstanceId(), current.runtimeRoomInstanceId())) {
            throw denied("retained placement operation is not the exact current APPLIED result");
          }

          RegionBinding region = readRegionBinding(request, association, current);
          byte[] originalEvidence = operation.originalLifecycleEvidenceBytes();
          if (!Arrays.equals(
                  originalEvidence, location.get("initial_lifecycle_evidence_bytes", byte[].class))
              || !Arrays.equals(
                  request.normalizedLifecycleEvidenceBytes(),
                  WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                      originalEvidence))) {
            throw denied("retained World lifecycle evidence differs from its exact placement");
          }
          authority.requireHeld();
          return Optional.of(
              new CurrentLocation(
                  request,
                  current,
                  association.worldInstanceId(),
                  current.startLocation(),
                  current.runtimeRoomInstanceId(),
                  region.worldRegionInstanceId(),
                  region.canonicalRegionInstanceId(),
                  region.operationalRegionId(),
                  result,
                  originalEvidence));
        });
  }

  private StoredOperation readOperation(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceAssociation association) {
    Record operation =
        dsl.fetchOne(
            "SELECT canonical_tenant_id, playable_state_namespace_id, canonical_game_instance_id, "
                + "world_instance_id, request_digest, request_bytes, original_lifecycle_evidence_bytes, "
                + "outcome, conflict_code, result_bytes, result_digest "
                + "FROM world_canonical_initial_player_location_operation "
                + "WHERE operation_id = ?",
            request.operationId());
    if (operation == null) return null;
    byte[] originalEvidence = operation.get("original_lifecycle_evidence_bytes", byte[].class);
    byte[] resultBytes = operation.get("result_bytes", byte[].class);
    if (!request.canonicalTenantId().equals(operation.get("canonical_tenant_id", UUID.class))
        || !request
            .playableStateNamespaceId()
            .equals(operation.get("playable_state_namespace_id", UUID.class))
        || !request
            .canonicalGameInstanceId()
            .equals(operation.get("canonical_game_instance_id", UUID.class))
        || association.worldInstanceId() != operation.get("world_instance_id", Long.class)
        || !request.requestDigest().equals(operation.get("request_digest", String.class))
        || !Arrays.equals(
            request.canonicalRequestBytes(), operation.get("request_bytes", byte[].class))
        || !Arrays.equals(
            request.normalizedLifecycleEvidenceBytes(),
            WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(originalEvidence))
        || !WorldCanonicalInitialPlayerLocationRepository.prefixedDigest(resultBytes)
            .equals(operation.get("result_digest", String.class))) {
      throw denied("World operation result differs from its exact request or retained proof");
    }
    WorldCanonicalInitialPlayerLocation.Result result;
    try {
      result = WorldCanonicalInitialPlayerLocation.Result.fromStored(request, resultBytes);
    } catch (IllegalArgumentException invalid) {
      throw denied("World operation result is not the exact canonical retained result");
    }
    if (!result.outcome().name().equals(operation.get("outcome", String.class))
        || !Objects.equals(result.conflictCode(), operation.get("conflict_code", String.class))) {
      throw denied("World operation result differs from its retained outcome");
    }
    return new StoredOperation(result, originalEvidence);
  }

  private RegionBinding readRegionBinding(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInstanceLifecycleEvidence current) {
    var fields = association.worldPrepareFields();
    Record region =
        dsl.fetchOne(
            "SELECT ri.id AS world_region_instance_id, ri.canonical_region_instance_id, "
                + "ri.operational_region_id "
                + "FROM world_canonical_preparation_start_location s "
                + "JOIN world_canonical_instance_topology_identity m "
                + "ON m.world_instance_id = s.world_instance_id "
                + "AND m.canonical_game_instance_id = s.canonical_game_instance_id "
                + "AND m.family = 'ROOM' AND m.template_id = s.room_template_id "
                + "JOIN room_instance r ON r.id = m.runtime_row_id "
                + "AND r.room_instance_row_id = s.runtime_room_instance_id "
                + "JOIN region_instance ri ON ri.id = r.region_instance_id "
                + "JOIN world_canonical_instance_topology_identity region_map "
                + "ON region_map.world_instance_id = s.world_instance_id "
                + "AND region_map.canonical_game_instance_id = s.canonical_game_instance_id "
                + "AND region_map.family = 'REGION' "
                + "AND region_map.runtime_row_id = ri.id "
                + "AND region_map.runtime_identity = ri.canonical_region_instance_id "
                + "WHERE s.canonical_game_instance_id = ? AND s.world_instance_id = ? "
                + "AND s.canonical_tenant_id = ? AND s.canonical_version_id = ? "
                + "AND s.room_template_id = ? AND s.runtime_room_instance_id = ? "
                + "AND m.runtime_room_instance_id = s.runtime_room_instance_id "
                + "AND m.runtime_row_id = s.runtime_room_instance_id "
                + "AND r.tenant_id = ? AND r.game_instance_id = ? "
                + "AND ri.tenant_id = ? AND ri.game_instance_id = ? AND ri.world_instance_id = ?",
            request.canonicalGameInstanceId(),
            association.worldInstanceId(),
            request.canonicalTenantId(),
            current.request().canonicalVersionId(),
            current.startLocation().roomTemplateId(),
            current.runtimeRoomInstanceId(),
            fields.privateTenantKey(),
            fields.privateGameInstanceKey(),
            fields.privateTenantKey(),
            fields.privateGameInstanceKey(),
            association.worldInstanceId());
    if (region == null) {
      throw denied("current V42 ROOM has no exact scoped World region row");
    }
    UUID operationalRegionId = region.get("operational_region_id", UUID.class);
    UUID canonicalRegionId = region.get("canonical_region_instance_id", UUID.class);
    if (canonicalRegionId == null
        || new UUID(0L, 0L).equals(canonicalRegionId)
        || operationalRegionId == null
        || new UUID(0L, 0L).equals(operationalRegionId)
        || operationalRegionId.equals(canonicalRegionId)) {
      throw denied(
          "current World region has invalid V35 identity or V45 operational assignment");
    }
    return new RegionBinding(
        region.get("world_region_instance_id", Long.class),
        canonicalRegionId,
        operationalRegionId);
  }

  private void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World current-location read requires a read-only REPEATABLE READ owner transaction");
    }
    Record mode =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (mode == null
        || !"repeatable read".equals(mode.get("isolation", String.class))
        || !"on".equals(mode.get("read_only", String.class))) {
      throw new IllegalStateException("World current-location snapshot has an unexpected mode");
    }
  }

  private static IllegalStateException denied(String message) {
    return new IllegalStateException("CURRENT_PLAYER_LOCATION_DENIED: " + message);
  }

  private record RegionBinding(
      long worldRegionInstanceId, UUID canonicalRegionInstanceId, UUID operationalRegionId) {}

  private record StoredOperation(
      WorldCanonicalInitialPlayerLocation.Result result, byte[] originalLifecycleEvidenceBytes) {
    private StoredOperation {
      originalLifecycleEvidenceBytes =
          Arrays.copyOf(originalLifecycleEvidenceBytes, originalLifecycleEvidenceBytes.length);
    }

    @Override
    public byte[] originalLifecycleEvidenceBytes() {
      return Arrays.copyOf(originalLifecycleEvidenceBytes, originalLifecycleEvidenceBytes.length);
    }
  }

  public record CurrentLocation(
      WorldCanonicalInitialPlayerLocation.Request binding,
      WorldCanonicalInstanceLifecycleEvidence currentLifecycleEvidence,
      long worldInstanceId,
      RoomTemplateRef startLocation,
      long runtimeRoomInstanceId,
      long worldRegionInstanceId,
      UUID canonicalRegionInstanceId,
      UUID operationalRegionId,
      WorldCanonicalInitialPlayerLocation.Result placementResult,
      byte[] originalLifecycleEvidenceBytes) {
    public CurrentLocation {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(currentLifecycleEvidence, "currentLifecycleEvidence");
      Objects.requireNonNull(startLocation, "startLocation");
      Objects.requireNonNull(canonicalRegionInstanceId, "canonicalRegionInstanceId");
      Objects.requireNonNull(operationalRegionId, "operationalRegionId");
      Objects.requireNonNull(placementResult, "placementResult");
      originalLifecycleEvidenceBytes =
          Arrays.copyOf(
              Objects.requireNonNull(
                  originalLifecycleEvidenceBytes, "originalLifecycleEvidenceBytes"),
              originalLifecycleEvidenceBytes.length);
      if (worldInstanceId <= 0
          || runtimeRoomInstanceId <= 0
          || worldRegionInstanceId <= 0
          || new UUID(0L, 0L).equals(canonicalRegionInstanceId)
          || new UUID(0L, 0L).equals(operationalRegionId)
          || canonicalRegionInstanceId.equals(operationalRegionId)
          || !"ACTIVE".equals(currentLifecycleEvidence.lifecycleStatus())
          || !startLocation.equals(currentLifecycleEvidence.startLocation())
          || runtimeRoomInstanceId != currentLifecycleEvidence.runtimeRoomInstanceId()
          || placementResult.outcome() != WorldCanonicalInitialPlayerLocation.Outcome.APPLIED
          || !binding.requestDigest().equals(placementResult.requestDigest())) {
        throw new IllegalArgumentException(
            "World current-location result is not an exact ACTIVE placement");
      }
    }

    @Override
    public byte[] originalLifecycleEvidenceBytes() {
      return Arrays.copyOf(originalLifecycleEvidenceBytes, originalLifecycleEvidenceBytes.length);
    }
  }
}
