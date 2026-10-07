package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * World-local initial actor placement storage. This repository has no transport registration and
 * its positive write result is not authenticated Entity assignment or gameplay readiness.
 */
public final class WorldCanonicalInitialPlayerLocationRepository {
  private final DSLContext dsl;
  private final TransactionTemplate writeTransaction;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final InitialAdmissionBindHoldRepository holdRepository;

  public WorldCanonicalInitialPlayerLocationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      InitialAdmissionBindHoldRepository holdRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository, "lifecycleRepository");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
    writeTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Rechecks current exact lifecycle, canonical association, and committed initial hold while the
   * World row is locked, then stores the first placement and immutable operation result atomically.
   * The caller must hold an operation-specific current-authority verifier through this commit.
   */
  public WorldCanonicalInitialPlayerLocation.Result place(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInitialPlayerLocationService.HeldPlacementAuthority authority) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(authority, "authority");
    requireNoActiveTransaction();
    return writeTransaction.execute(
        status -> {
          requireWritableTransaction();
          authority.requireHeld();
          WorldCanonicalInstanceLifecycleEvidence current =
              lifecycleRepository
                  .readForActivationInOwnerTransaction(request.activeLifecycleEvidence().request())
                  .orElseThrow(
                      () -> denied("exact canonical World lifecycle association is missing"));
          if (!"ACTIVE".equals(current.lifecycleStatus())
              || !Arrays.equals(
                  request.normalizedLifecycleEvidenceBytes(),
                  WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                      current.canonicalBytes()))) {
            throw denied(
                "current ACTIVE lifecycle evidence differs from the complete request proof");
          }

          WorldCanonicalInstanceAssociation association =
              associationRepository
                  .readOwnerAssociationInActivationTransaction(request.canonicalGameInstanceId())
                  .orElseThrow(() -> denied("exact canonical World association is missing"));
          requireAssociation(request, current, association);
          InitialAdmissionBindHold hold =
              holdRepository
                  .findByHoldIdForUpdate(request.initialAdmissionHoldId().toString())
                  .orElseThrow(() -> denied("initial-admission hold is missing"));
          requireCommittedNoPriorPointerHold(request, current, association, hold);

          Optional<StoredOperation> prior = findOperation(request);
          if (prior.isPresent()) {
            StoredOperation stored = prior.orElseThrow();
            if (!request.requestDigest().equals(stored.requestDigest())
                || !Arrays.equals(request.canonicalRequestBytes(), stored.requestBytes())
                || !Arrays.equals(
                    request.normalizedLifecycleEvidenceBytes(),
                    WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                        stored.originalLifecycleEvidenceBytes()))) {
              throw conflict("initial-location operation identity was reused with changed input");
            }
            var result =
                WorldCanonicalInitialPlayerLocation.Result.fromStored(
                    request, stored.resultBytes());
            if (result.outcome() == WorldCanonicalInitialPlayerLocation.Outcome.APPLIED) {
              List<Record> locations = findExistingLocations(request);
              if (locations.size() != 1
                  || !sameInitialPlacement(request, current, locations.get(0))
                  || !result.startLocation().equals(current.startLocation())
                  || !Objects.equals(
                      result.runtimeRoomInstanceId(), current.runtimeRoomInstanceId())) {
                throw denied("retained APPLIED result has no exact current immutable location row");
              }
            }
            return result;
          }

          List<Record> existingLocations = findExistingLocations(request);
          boolean splitIdentityConflict = existingLocations.size() > 1;
          Record existing = existingLocations.size() == 1 ? existingLocations.get(0) : null;
          boolean samePlacement =
              existing != null && sameInitialPlacement(request, current, existing);
          var result =
              splitIdentityConflict
                  ? WorldCanonicalInitialPlayerLocation.Result.conflict(
                      request, "CHARACTER_AND_ASSIGNMENT_IDENTIFY_DIFFERENT_PLACEMENTS")
                  : samePlacement
                      ? WorldCanonicalInitialPlayerLocation.Result.applied(
                          request, current.startLocation(), current.runtimeRoomInstanceId())
                      : existing == null
                          ? WorldCanonicalInitialPlayerLocation.Result.applied(
                              request, current.startLocation(), current.runtimeRoomInstanceId())
                          : WorldCanonicalInitialPlayerLocation.Result.conflict(
                              request, "INITIAL_LOCATION_ALREADY_ASSIGNED");

          insertOperation(request, association.worldInstanceId(), result);
          if (result.outcome() == WorldCanonicalInitialPlayerLocation.Outcome.APPLIED
              && existing == null) {
            insertLocation(request, current, association.worldInstanceId());
          }
          authority.requireHeld();
          return result;
        });
  }

  private void requireAssociation(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current,
      WorldCanonicalInstanceAssociation association) {
    var identity = association.identity();
    var lifecycle = current.request();
    if (!request.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())
        || !request.canonicalTenantId().equals(identity.canonicalTenantId())
        || !request.worldSlug().equals(identity.worldSlug())
        || !request.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        || !request.playableStateScope().equals(identity.playableStateScope())
        || !request.canonicalTenantId().equals(lifecycle.canonicalTenantId())
        || !request.worldSlug().equals(lifecycle.worldSlug())
        || !request.canonicalGameInstanceId().equals(lifecycle.canonicalGameInstanceId())
        || !request.playableStateNamespaceId().equals(lifecycle.playableStateNamespaceId())
        || !request.playableStateScope().equals(lifecycle.playableStateScope())
        || !request.canonicalTenantId().equals(current.startLocation().tenantId())
        || !lifecycle.canonicalVersionId().equals(current.startLocation().versionId())) {
      throw denied(
          "canonical placement scope differs from the exact World association or ROOM selector");
    }
  }

  private static void requireCommittedNoPriorPointerHold(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current,
      WorldCanonicalInstanceAssociation association,
      InitialAdmissionBindHold hold) {
    var fields = association.worldPrepareFields();
    if (!WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.NO_PRIOR_POINTER.equals(
            request.initialAdmissionOrigin())
        || !"COMMITTED".equals(hold.status())
        || !hold.expectedNoPriorPointer()
        || !request.initialAdmissionHoldId().toString().equals(hold.holdId())
        || !request.initialAdmissionHoldFence().toString().equals(hold.holdFence())
        || hold.tenantId() != fields.privateTenantKey()
        || hold.gameInstanceId() != fields.privateGameInstanceKey()
        || hold.versionId() != fields.localVersionKey()
        || !request.realmId().toString().equals(hold.realmUuid())
        || !request.playableStateNamespaceId().toString().equals(hold.playableStateNamespaceUuid())
        || !request.playableStateScope().equals(hold.playableStateScope())
        || !request.initialAdmissionRequestId().equals(hold.initialAdmissionRequestId())
        || !request.initialAdmissionRequestDigest().equals(hold.requestDigest())
        || hold.expectedCatalogRevision() != request.catalogRevision()
        || !request.initialAdmissionOwnerProofId().equals(hold.ownerProofId())
        || !request.initialAdmissionOwnerProofDigest().equals(hold.ownerProofDigest())
        || !request.pointerAuditId().equals(hold.ownerPointerAuditId())
        || !Objects.equals(request.pointerVersion(), hold.ownerPointerVersion())
        || hold.activeLifecycleEpoch() != current.lifecycleEpoch()) {
      throw denied(
          "initial placement requires the exact committed NO_PRIOR_POINTER hold and pointer outcome");
    }
  }

  private Optional<StoredOperation> findOperation(
      WorldCanonicalInitialPlayerLocation.Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT request_digest, request_bytes, original_lifecycle_evidence_bytes, result_bytes FROM "
                + "world_canonical_initial_player_location_operation "
                + "WHERE operation_id = ?",
            request.operationId());
    if (row == null) return Optional.empty();
    return Optional.of(
        new StoredOperation(
            row.get("request_digest", String.class),
            row.get("request_bytes", byte[].class),
            row.get("original_lifecycle_evidence_bytes", byte[].class),
            row.get("result_bytes", byte[].class)));
  }

  private List<Record> findExistingLocations(WorldCanonicalInitialPlayerLocation.Request request) {
    return dsl.fetch(
        "SELECT canonical_tenant_id, realm_id, world_slug, playable_state_namespace_id, "
            + "playable_state_scope, canonical_account_id, character_id, entity_assignment_operation_id, "
            + "entity_assignment_digest, canonical_version_id, active_lifecycle_epoch, "
            + "initial_room_template_id, initial_runtime_room_instance_id, runtime_room_instance_id, initial_admission_hold_id, "
            + "initial_admission_hold_fence, initial_admission_request_id, "
            + "initial_admission_request_digest, catalog_revision, initial_admission_owner_proof_id, "
            + "initial_admission_owner_proof_digest, pointer_audit_id, pointer_version, "
            + "initial_lifecycle_evidence_bytes, initial_lifecycle_evidence_digest "
            + "FROM character_location WHERE canonical_game_instance_id = ? "
            + "AND playable_state_namespace_id = ? AND (character_id = ? "
            + "OR entity_assignment_operation_id = ?) FOR UPDATE",
        request.canonicalGameInstanceId(),
        request.playableStateNamespaceId(),
        request.characterId(),
        request.entityAssignmentOperationId());
  }

  private static boolean sameInitialPlacement(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current,
      Record existing) {
    return request.canonicalTenantId().equals(existing.get("canonical_tenant_id", UUID.class))
        && request.realmId().equals(existing.get("realm_id", UUID.class))
        && request.worldSlug().equals(existing.get("world_slug", String.class))
        && request
            .playableStateNamespaceId()
            .equals(existing.get("playable_state_namespace_id", UUID.class))
        && request.playableStateScope().equals(existing.get("playable_state_scope", String.class))
        && request.canonicalAccountId().equals(existing.get("canonical_account_id", UUID.class))
        && request.characterId().equals(existing.get("character_id", UUID.class))
        && request
            .entityAssignmentOperationId()
            .equals(existing.get("entity_assignment_operation_id", UUID.class))
        && request
            .entityAssignmentDigest()
            .equals(existing.get("entity_assignment_digest", String.class))
        && current
            .request()
            .canonicalVersionId()
            .equals(existing.get("canonical_version_id", UUID.class))
        && current.lifecycleEpoch() == existing.get("active_lifecycle_epoch", Long.class)
        && current
            .startLocation()
            .roomTemplateId()
            .equals(existing.get("initial_room_template_id", UUID.class))
        && current.runtimeRoomInstanceId()
            == existing.get("initial_runtime_room_instance_id", Long.class)
        && current.runtimeRoomInstanceId() == existing.get("runtime_room_instance_id", Long.class)
        && request
            .initialAdmissionHoldId()
            .equals(existing.get("initial_admission_hold_id", UUID.class))
        && request
            .initialAdmissionHoldFence()
            .equals(existing.get("initial_admission_hold_fence", UUID.class))
        && request
            .initialAdmissionRequestId()
            .equals(existing.get("initial_admission_request_id", String.class))
        && request
            .initialAdmissionRequestDigest()
            .equals(existing.get("initial_admission_request_digest", String.class))
        && request.catalogRevision() == existing.get("catalog_revision", Long.class)
        && request
            .initialAdmissionOwnerProofId()
            .equals(existing.get("initial_admission_owner_proof_id", String.class))
        && request
            .initialAdmissionOwnerProofDigest()
            .equals(existing.get("initial_admission_owner_proof_digest", String.class))
        && request.pointerAuditId().equals(existing.get("pointer_audit_id", String.class))
        && request.pointerVersion() == existing.get("pointer_version", Long.class)
        && Objects.equals(
            existing.get("initial_lifecycle_evidence_digest", String.class),
            prefixedDigest(existing.get("initial_lifecycle_evidence_bytes", byte[].class)))
        && Arrays.equals(
            request.normalizedLifecycleEvidenceBytes(),
            WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                existing.get("initial_lifecycle_evidence_bytes", byte[].class)));
  }

  private void insertOperation(
      WorldCanonicalInitialPlayerLocation.Request request,
      long worldInstanceId,
      WorldCanonicalInitialPlayerLocation.Result result) {
    byte[] requestBytes = request.canonicalRequestBytes();
    byte[] resultBytes = result.canonicalBytes();
    int inserted =
        dsl.execute(
            "INSERT INTO world_canonical_initial_player_location_operation "
                + "(canonical_tenant_id, playable_state_namespace_id, canonical_game_instance_id, "
                + "operation_id, world_instance_id, request_digest, request_bytes, "
                + "original_lifecycle_evidence_bytes, outcome, conflict_code, result_bytes, result_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            request.canonicalTenantId(),
            request.playableStateNamespaceId(),
            request.canonicalGameInstanceId(),
            request.operationId(),
            worldInstanceId,
            request.requestDigest(),
            requestBytes,
            request.originalLifecycleEvidenceBytes(),
            result.outcome().name(),
            result.conflictCode(),
            resultBytes,
            prefixedDigest(resultBytes));
    if (inserted != 1) throw conflict("initial-location operation was concurrently recorded");
  }

  private void insertLocation(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current,
      long worldInstanceId) {
    int inserted =
        dsl.execute(
            "INSERT INTO character_location "
                + "(canonical_tenant_id, realm_id, world_slug, playable_state_namespace_id, "
                + "playable_state_scope, canonical_game_instance_id, world_instance_id, "
                + "canonical_account_id, character_id, entity_assignment_operation_id, "
                + "entity_assignment_digest, canonical_version_id, active_lifecycle_epoch, "
                + "initial_room_template_id, initial_runtime_room_instance_id, runtime_room_instance_id, "
                + "initial_admission_hold_id, initial_admission_hold_fence, initial_admission_request_id, "
                + "initial_admission_request_digest, catalog_revision, initial_admission_owner_proof_id, "
                + "initial_admission_owner_proof_digest, pointer_audit_id, pointer_version, "
                + "initial_location_operation_id, initial_location_request_digest, "
                + "initial_lifecycle_evidence_bytes, initial_lifecycle_evidence_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            request.canonicalTenantId(),
            request.realmId(),
            request.worldSlug(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            worldInstanceId,
            request.canonicalAccountId(),
            request.characterId(),
            request.entityAssignmentOperationId(),
            request.entityAssignmentDigest(),
            current.request().canonicalVersionId(),
            current.lifecycleEpoch(),
            current.startLocation().roomTemplateId(),
            current.runtimeRoomInstanceId(),
            current.runtimeRoomInstanceId(),
            request.initialAdmissionHoldId(),
            request.initialAdmissionHoldFence(),
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            request.catalogRevision(),
            request.initialAdmissionOwnerProofId(),
            request.initialAdmissionOwnerProofDigest(),
            request.pointerAuditId(),
            request.pointerVersion(),
            request.operationId(),
            request.requestDigest(),
            request.originalLifecycleEvidenceBytes(),
            prefixedDigest(request.originalLifecycleEvidenceBytes()));
    if (inserted != 1) {
      throw conflict("initial character location conflicts with an existing actor assignment");
    }
  }

  private static void requireNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World initial placement must start outside an ambient transaction");
    }
  }

  private void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World initial placement requires a writable READ COMMITTED transaction");
    }
    Record state =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, current_setting('transaction_read_only') AS read_only");
    if (state == null
        || !"read committed".equals(state.get("isolation", String.class))
        || !"off".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException("World initial placement transaction has an unexpected mode");
    }
  }

  private static String prefixedDigest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalStateException denied(String message) {
    return new IllegalStateException("INITIAL_PLAYER_LOCATION_DENIED: " + message);
  }

  private static IllegalArgumentException conflict(String message) {
    return new IllegalArgumentException("IDEMPOTENCY_CONFLICT: " + message);
  }

  private record StoredOperation(
      String requestDigest,
      byte[] requestBytes,
      byte[] originalLifecycleEvidenceBytes,
      byte[] resultBytes) {
    private StoredOperation {
      requestBytes = Arrays.copyOf(requestBytes, requestBytes.length);
      originalLifecycleEvidenceBytes =
          Arrays.copyOf(originalLifecycleEvidenceBytes, originalLifecycleEvidenceBytes.length);
      resultBytes = Arrays.copyOf(resultBytes, resultBytes.length);
    }
  }
}
