package net.firedevops.firemud.entitymanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered owner-local retention source. Every operation requires a writable owner transaction.
 *
 * <p>The integrating authenticated owner adapter must validate acquire/release authority. This
 * repository does not authenticate Java callers, accept terminal booleans or provide disposal.
 * Authenticated original-operation carriers bind immutable snapshot receipts atomically. Current
 * source evidence cannot attest complete consumer/restore closure, so snapshots remain protected
 * even after locally recorded non-original references are released.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "DSLContext is the owner transaction collaborator")
public final class CanonicalGameplayRosterRetentionRepository {
  private final DSLContext dsl;

  public CanonicalGameplayRosterRetentionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  public UUID reserveAdmission(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    requireOwnerTransaction();
    requireNoRetirementTransfer(request.requestUuid());
    byte[] binding = admissionBinding(request);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        request.requestUuid(),
        UUID.randomUUID(),
        binding);
    Record original =
        dsl.fetchSingle(
            "SELECT admission_uuid, request_binding, admission_state FROM entity_canonical_roster_admissions WHERE operation_uuid = ? FOR UPDATE",
            request.requestUuid());
    requireNoRetirementTransfer(request.requestUuid());
    if (!Arrays.equals(binding, original.get("request_binding", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_REQUEST_CONFLICT");
    }
    if (!"OPEN".equals(original.get("admission_state", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_DISPOSED");
    }
    return original.get("admission_uuid", UUID.class);
  }

  public void requireAdmission(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request,
      UUID nonce) {
    requireOwnerTransaction();
    Record original =
        dsl.fetchOne(
            "SELECT request_binding FROM entity_canonical_roster_admissions WHERE operation_uuid = ? "
                + "AND admission_uuid = ? AND admission_state = 'OPEN' FOR UPDATE",
            request.requestUuid(),
            nonce);
    requireNoRetirementTransfer(request.requestUuid());
    if (original == null
        || !Arrays.equals(
            admissionBinding(request), original.get("request_binding", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
  }

  public void bindAdmission(UUID operation, UUID nonce, UUID snapshot) {
    requireOwnerTransaction();
    requireNoRetirementTransfer(operation);
    int changed =
        dsl.execute(
            "UPDATE entity_canonical_roster_admissions SET snapshot_uuid = ? WHERE operation_uuid = ? AND admission_uuid = ? "
                + "AND admission_state = 'OPEN' AND (snapshot_uuid IS NULL OR snapshot_uuid = ?)",
            snapshot,
            operation,
            nonce,
            snapshot);
    if (changed != 1) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
  }

  private static byte[] admissionBinding(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setRequestUuid(request.requestUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setExpectedTarget(request.expectedTarget().toWire())
        .setPlayerExecutionContext(
            net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                .setSessionId(request.playerExecutionContext().sessionUuid().toString()))
        .build()
        .toByteArray();
  }

  private static byte[] admissionBinding(GetCanonicalGameplayRosterReferenceRequest request) {
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setRequestUuid(request.getOperationUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setExpectedTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
        .setPlayerExecutionContext(
            net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                .setSessionId(request.getSessionUuid()))
        .build()
        .toByteArray();
  }

  /**
   * Closes the exact local nonce without resolving a target or releasing any snapshot reference.
   */
  public CanonicalGameplayRosterRecoveryResponse closeAdmission(
      CanonicalGameplayRosterRecoveryRequest request,
      GetCanonicalGameplayRosterReferenceResponse owner,
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy
          policy) {
    CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(request, owner);
    Objects.requireNonNull(policy, "policy");
    requireOwnerTransaction();
    var original = request.getOriginalSelection();
    UUID operation = UUID.fromString(original.getOperationUuid());
    byte[] binding = admissionBinding(original);
    // The provisional insert and closure commit together. Normal handlers can never observe an
    // OPEN admission created by this transaction; conflict paths lock the existing nonce first.
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        operation,
        UUID.randomUUID(),
        binding);
    Record admission =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_admissions WHERE operation_uuid = ? FOR UPDATE",
            operation);
    if (!Arrays.equals(binding, admission.get("request_binding", byte[].class))) {
      throw new RecoveryConflictException("CANONICAL_ROSTER_LOCAL_ADMISSION_REQUEST_CONFLICT");
    }
    String state = admission.get("admission_state", String.class);
    if ("CLOSED".equals(state) || "DISPOSED".equals(state)) {
      if (!Arrays.equals(request.toByteArray(), admission.get("recovery_request", byte[].class))
          || !Objects.equals(
              owner.getOwnerFence(), admission.get("recovery_owner_fence", Long.class))
          || !owner
              .getTargetFenceUuid()
              .equals(String.valueOf(admission.get("recovery_target_fence_uuid", UUID.class)))) {
        throw new RecoveryConflictException("CANONICAL_ROSTER_RECOVERY_CONFLICT");
      }
      try {
        var retained =
            CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                request,
                CanonicalGameplayRosterRecoveryResponse.parseFrom(
                    admission.get("recovery_response", byte[].class)));
        if (!owner.getCanonicalRosterReceipt().isEmpty()
            && !owner.getCanonicalRosterReceipt().equals(retained.getCanonicalRosterReceipt())) {
          throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_LOCAL_RECEIPT_MISMATCH");
        }
        return retained;
      } catch (com.google.protobuf.InvalidProtocolBufferException
          | IllegalArgumentException invalid) {
        throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_RECEIPT_INVALID", invalid);
      }
    }
    if (!"OPEN".equals(state)) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
    var receipt = originalReceipt(owner).orElse(null);
    UUID snapshot = admission.get("snapshot_uuid", UUID.class);
    if ((snapshot == null) != (receipt == null)
        || (snapshot != null && !snapshot.toString().equals(receipt.getSnapshotUuid()))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_LOCAL_RECEIPT_MISMATCH");
    }
    var response =
        CanonicalGameplayRosterRecoveryEvidence.response(
            request,
            admission.get("admission_uuid", UUID.class),
            owner.getOwnerFence(),
            UUID.fromString(owner.getTargetFenceUuid()),
            receipt);
    dsl.execute(
        "UPDATE entity_canonical_roster_admissions SET admission_state = 'CLOSED', closed_at = clock_timestamp(), "
            + "minimum_retention_millis = ?, recovery_uuid = ?, recovery_request = ?, recovery_response = ?, "
            + "recovery_owner_request = ?, recovery_owner_fence = ?, recovery_target_fence_uuid = ? WHERE operation_uuid = ?",
        policy.minimumRetention().toMillis(),
        UUID.fromString(request.getRecoveryUuid()),
        request.toByteArray(),
        response.toByteArray(),
        original.toByteArray(),
        owner.getOwnerFence(),
        UUID.fromString(owner.getTargetFenceUuid()),
        operation);
    return response;
  }

  /** Exact caller request/recovery identity conflict, distinct from invalid retained state. */
  public static final class RecoveryConflictException extends IllegalStateException {
    public RecoveryConflictException(String message) {
      super(message);
    }
  }

  public record ClosedAdmissionCandidate(
      UUID operationUuid, UUID admissionUuid, CanonicalGameplayRosterRecoveryResponse response) {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public CanonicalGameplayRosterRecoveryResponse response() {
      return response;
    }
  }

  /** Bound closures stay with snapshot disposal; unknown owner outcomes remain retained. */
  public java.util.List<ClosedAdmissionCandidate> closedAdmissionCandidates(UUID after, int limit) {
    requireOwnerTransaction();
    if (limit <= 0) throw new IllegalArgumentException("Bounded closure page required");
    return dsl.fetch(
            "SELECT operation_uuid, admission_uuid, recovery_response FROM entity_canonical_roster_admissions "
                + "WHERE admission_state = 'CLOSED' AND snapshot_uuid IS NULL "
                + "AND (?::uuid IS NULL OR operation_uuid > ?::uuid) ORDER BY operation_uuid LIMIT ?",
            after,
            after,
            limit)
        .map(
            row -> {
              try {
                var response =
                    CanonicalGameplayRosterRecoveryResponse.parseFrom(
                        row.get("recovery_response", byte[].class));
                CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                    response.getRequest(), response);
                return new ClosedAdmissionCandidate(
                    row.get("operation_uuid", UUID.class),
                    row.get("admission_uuid", UUID.class),
                    response);
              } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(
                    "CANONICAL_ROSTER_RECOVERY_RECEIPT_INVALID", invalid);
              }
            });
  }

  /**
   * Fresh independently read terminal acknowledgement; time or absence is never acknowledgement.
   */
  public boolean purgeClosedAdmission(
      ClosedAdmissionCandidate candidate, GetCanonicalGameplayRosterReferenceResponse owner) {
    var response = candidate.response();
    CanonicalGameplayRosterRecoveryEvidence.requireResponse(response.getRequest(), response);
    CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(response.getRequest(), owner);
    if (owner.getOwnerFence() != response.getOwnerFence()
        || !owner.getTargetFenceUuid().equals(response.getTargetFenceUuid())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_CONFLICT");
    }
    String terminal;
    if (owner.getState()
        == net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
            .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED) {
      CanonicalGameplayRosterReferenceEvidence.requireRetirement(owner);
      terminal = "RETIRED";
    } else if ("DENIED".equals(owner.getTerminalOutcome())) {
      terminal = "DENIED";
    } else {
      return false;
    }
    requireOwnerTransaction();
    Record admission =
        dsl.fetchOne(
            "SELECT *, closed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ? FOR UPDATE",
            candidate.operationUuid(),
            candidate.admissionUuid());
    if (admission == null
        || !"CLOSED".equals(admission.get("admission_state", String.class))
        || admission.get("snapshot_uuid", UUID.class) != null
        || !Arrays.equals(
            response.toByteArray(), admission.get("recovery_response", byte[].class))) {
      return false;
    }
    queueRetirementTransfer(
        response.getRequest().getOriginalSelection(),
        candidate.admissionUuid(),
        null,
        null,
        response,
        owner.getOwnerFence(),
        UUID.fromString(owner.getTargetFenceUuid()),
        admission.get("minimum_retention_millis", Long.class),
        "RETIRED".equals(terminal) ? owner : null);
    if (!Boolean.TRUE.equals(admission.get("horizon_passed", Boolean.class))) return false;
    dsl.execute(
        "UPDATE entity_canonical_roster_admissions SET recovery_acknowledgement = ?, recovery_acknowledgement_state = ?, "
            + "recovery_acknowledgement_uuid = ?, recovery_acknowledgement_owner_fence = ?, recovery_acknowledgement_target_fence_uuid = ? "
            + "WHERE operation_uuid = ?",
        owner.toByteArray(),
        terminal,
        UUID.fromString(owner.getRecoveryUuid()),
        owner.getOwnerFence(),
        UUID.fromString(owner.getTargetFenceUuid()),
        candidate.operationUuid());
    return dsl.execute(
            "DELETE FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ?",
            candidate.operationUuid(),
            candidate.admissionUuid())
        == 1;
  }

  /**
   * Revokes only ownerless local nonces after the complete independently configured local horizon.
   */
  public int purgeOrphanOpenAdmissions(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy policy,
      int limit) {
    Objects.requireNonNull(policy, "policy");
    requireOwnerTransaction();
    if (limit <= 0) throw new IllegalArgumentException("Bounded orphan page required");
    var expired =
        dsl.fetch(
            "UPDATE entity_canonical_roster_admissions SET admission_state = 'EXPIRED', minimum_retention_millis = ? "
                + "WHERE operation_uuid IN (SELECT operation_uuid FROM entity_canonical_roster_admissions "
                + "WHERE admission_state = 'OPEN' AND snapshot_uuid IS NULL "
                + "AND recorded_at + ? * interval '1 millisecond' <= clock_timestamp() "
                + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = entity_canonical_roster_admissions.operation_uuid) "
                + "ORDER BY operation_uuid LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING operation_uuid",
            policy.minimumRetention().toMillis(),
            policy.minimumRetention().toMillis(),
            limit);
    int removed = 0;
    for (Record row : expired) {
      removed +=
          dsl.execute(
              "DELETE FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_state = 'EXPIRED'",
              row.get("operation_uuid", UUID.class));
    }
    return removed;
  }

  /** Serializes recovery of the same original operation before any new snapshot capture. */
  public Optional<CanonicalGameplayRosterResponse> originalReceipt(
      GetCanonicalGameplayRosterReferenceResponse owner) {
    requireOwnerTransaction();
    var request = owner.getRequest();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, owner);
    UUID operation = CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid());
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "entity-roster-original:" + operation);
    Record original =
        dsl.fetchOne(
            "SELECT owner_request, owner_fence, owner_target_fence_uuid, canonical_roster_receipt "
                + "FROM entity_canonical_roster_original_selections WHERE operation_uuid = ? FOR UPDATE",
            operation);
    if (original == null) {
      return Optional.empty();
    }
    if (!owner
            .getTargetFenceUuid()
            .equals(original.get("owner_target_fence_uuid", UUID.class).toString())
        || owner.getOwnerFence() != original.get("owner_fence", Long.class)
        || !Arrays.equals(request.toByteArray(), original.get("owner_request", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    }
    try {
      var receipt =
          CanonicalGameplayRosterResponse.parseFrom(
              original.get("canonical_roster_receipt", byte[].class));
      CanonicalGameplayRosterReferenceEvidence.response(
          request,
          owner.getOwnerFence(),
          java.util.UUID.fromString(owner.getTargetFenceUuid()),
          receipt);
      if (!owner.getCanonicalRosterReceipt().isEmpty()
          && !owner.getCanonicalRosterReceipt().equals(receipt.toByteString())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
      }
      return Optional.of(receipt);
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_INVALID", invalid);
    }
  }

  /** Must join snapshot capture's transaction; a failed reference bind rolls the capture back. */
  public void bindOriginal(
      GetCanonicalGameplayRosterReferenceResponse owner, CanonicalGameplayRosterSnapshot snapshot) {
    requireOwnerTransaction();
    var request = owner.getRequest();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, owner);
    CanonicalGameplayRosterReferenceEvidence.response(
        request,
        owner.getOwnerFence(),
        java.util.UUID.fromString(owner.getTargetFenceUuid()),
        snapshot.toWire());
    UUID operation = CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid());
    var binding =
        new CanonicalGameplayRosterRetentionBinding(
            snapshot.canonicalAccountUuid(),
            new net.firedevops.firemud.entitymanagement.service
                .CanonicalGameplayRosterSnapshotReference(
                snapshot.snapshotUuid(), snapshot.snapshotDigest()),
            snapshot.target());
    acquire(binding, operation, operation, ReferenceKind.ORIGINAL_SELECTION);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_original_selections "
            + "(operation_uuid, owner_request, owner_fence, owner_target_fence_uuid, snapshot_uuid, snapshot_digest, canonical_roster_receipt) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        operation,
        request.toByteArray(),
        owner.getOwnerFence(),
        UUID.fromString(owner.getTargetFenceUuid()),
        snapshot.snapshotUuid(),
        snapshot.snapshotDigest(),
        snapshot.toWire().toByteArray());
  }

  public record Candidate(
      CanonicalGameplayRosterRetentionBinding binding,
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest ownerRequest,
      long localEpoch,
      String lifecycleState,
      String proofDigest) {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
        ownerRequest() {
      return ownerRequest;
    }
  }

  /** A bounded UUID page permits later sweeps to pass protected/unavailable owners. */
  public java.util.List<Candidate> candidates(UUID afterSnapshot, int limit) {
    requireOwnerTransaction();
    if (limit <= 0) {
      throw new IllegalArgumentException("Bounded roster candidate page required");
    }
    return dsl.fetch(
            "SELECT DISTINCT ON (o.snapshot_uuid) o.snapshot_uuid, o.snapshot_digest, o.owner_request, "
                + "l.local_epoch, l.lifecycle_state, l.owner_retirement_proof_digest FROM entity_canonical_roster_original_selections o "
                + "JOIN entity_canonical_roster_retention_lifecycle l USING (snapshot_uuid, snapshot_digest) "
                + "WHERE (?::uuid IS NULL OR o.snapshot_uuid > ?::uuid) ORDER BY o.snapshot_uuid, o.operation_uuid LIMIT ?",
            afterSnapshot,
            afterSnapshot,
            limit)
        .map(
            row -> {
              try {
                var request =
                    net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                        .parseFrom(row.get("owner_request", byte[].class));
                CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
                var target =
                    net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget
                        .fromWire(CanonicalGameplayRosterReferenceEvidence.target(request));
                var binding =
                    new CanonicalGameplayRosterRetentionBinding(
                        UUID.fromString(request.getCanonicalAccountUuid()),
                        new net.firedevops.firemud.entitymanagement.service
                            .CanonicalGameplayRosterSnapshotReference(
                            row.get("snapshot_uuid", UUID.class),
                            row.get("snapshot_digest", String.class)),
                        target);
                return new Candidate(
                    binding,
                    request,
                    row.get("local_epoch", Long.class),
                    row.get("lifecycle_state", String.class),
                    row.get("owner_retirement_proof_digest", String.class));
              } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(
                    "CANONICAL_ROSTER_ORIGINAL_REQUEST_INVALID", invalid);
              }
            });
  }

  /** Authenticated immutable GS target-seal evidence is observed on Entity's independent clock. */
  public boolean observeRetirement(
      Candidate candidate,
      GetCanonicalGameplayRosterReferenceResponse proof,
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy
          policy) {
    CanonicalGameplayRosterReferenceEvidence.requireResponse(candidate.ownerRequest(), proof);
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
    var receipt = CanonicalGameplayRosterReferenceEvidence.receipt(proof);
    if (!candidate.binding().snapshot().snapshotUuid().toString().equals(receipt.getSnapshotUuid())
        || !candidate.binding().snapshot().snapshotDigest().equals(receipt.getSnapshotDigest())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_SNAPSHOT_MISMATCH");
    }
    requireOwnerTransaction();
    // Capture takes the admission nonce before any snapshot lock; observation uses that order too.
    lockSnapshotAdmissions(candidate.binding().snapshot().snapshotUuid());
    lock(candidate.binding());
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            candidate.binding().snapshot().snapshotUuid());
    if (candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)) {
      return false;
    }
    if (!"PROTECTED".equals(lifecycle.get("lifecycle_state", String.class))) {
      boolean same =
          proof
                  .getTargetFenceUuid()
                  .equals(lifecycle.get("owner_target_fence_uuid", UUID.class).toString())
              && proof
                  .getRetirementUuid()
                  .equals(lifecycle.get("owner_retirement_uuid", UUID.class).toString());
      if (same) {
        try {
          queueBoundRetirementTransfers(
              candidate.binding().snapshot().snapshotUuid(),
              GetCanonicalGameplayRosterReferenceResponse.parseFrom(
                  lifecycle.get("owner_retirement_proof", byte[].class)),
              lifecycle.get("minimum_retention_millis", Long.class));
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
          throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_PROOF_INVALID", invalid);
        }
      }
      return same;
    }
    var originals =
        dsl.fetch(
            "SELECT o.operation_uuid, o.owner_fence, o.owner_target_fence_uuid, o.owner_request "
                + "FROM entity_canonical_roster_original_selections o WHERE snapshot_uuid = ? FOR UPDATE",
            candidate.binding().snapshot().snapshotUuid());
    for (Record original : originals) {
      if (original.get("owner_fence", Long.class) != proof.getOwnerFence()
          || !original
              .get("owner_target_fence_uuid", UUID.class)
              .toString()
              .equals(proof.getTargetFenceUuid())) {
        return false;
      }
      try {
        var request =
            net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                .parseFrom(original.get("owner_request", byte[].class));
        if (!request.getCanonicalAccountUuid().equals(proof.getRequest().getCanonicalAccountUuid())
            || !request.getTargetNamespace().equals(proof.getRequest().getTargetNamespace())
            || !request.getCanonicalTarget().equals(proof.getRequest().getCanonicalTarget())) {
          return false;
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
        throw new IllegalStateException(invalid);
      }
    }
    // Preserve unknown local recovery/hold references. Only original references attested by the
    // exact sealed GS target fence are released here, in the same local transaction.
    for (Record original : originals) {
      UUID reference = original.get("operation_uuid", UUID.class);
      UUID release =
          UUID.nameUUIDFromBytes(
              (proof.getRetirementUuid() + ":" + reference)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      dsl.execute(
          "INSERT INTO entity_canonical_roster_retention_releases "
              + "(release_operation_uuid, reference_uuid, snapshot_uuid, snapshot_digest) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
          release,
          reference,
          candidate.binding().snapshot().snapshotUuid(),
          candidate.binding().snapshot().snapshotDigest());
    }
    dsl.execute(
        "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'RETIRED', local_epoch = local_epoch + 1, "
            + "owner_target_fence_uuid = ?, owner_retirement_uuid = ?, owner_retirement_proof_digest = ?, owner_retirement_proof = ?, "
            + "retirement_observed_at = clock_timestamp(), minimum_retention_millis = ? WHERE snapshot_uuid = ? AND lifecycle_state = 'PROTECTED'",
        UUID.fromString(proof.getTargetFenceUuid()),
        UUID.fromString(proof.getRetirementUuid()),
        proof.getRetirementProofDigest(),
        proof.toByteArray(),
        policy.minimumRetention().toMillis(),
        candidate.binding().snapshot().snapshotUuid());
    queueBoundRetirementTransfers(
        candidate.binding().snapshot().snapshotUuid(), proof, policy.minimumRetention().toMillis());
    return true;
  }

  /** All deletes are guarded by exact persisted owner proof, local epoch and current references. */
  public boolean dispose(Candidate candidate) {
    requireOwnerTransaction();
    // The same admission-before-snapshot lock order fences delayed duplicate handlers.
    dsl.fetch(
        "SELECT admission_uuid FROM entity_canonical_roster_admissions WHERE snapshot_uuid = ? ORDER BY operation_uuid FOR UPDATE",
        candidate.binding().snapshot().snapshotUuid());
    lock(candidate.binding());
    UUID snapshot = candidate.binding().snapshot().snapshotUuid();
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT *, retirement_observed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            snapshot);
    if ("RETIRED".equals(lifecycle.get("lifecycle_state", String.class))) {
      try {
        var observed =
            GetCanonicalGameplayRosterReferenceResponse.parseFrom(
                lifecycle.get("owner_retirement_proof", byte[].class));
        CanonicalGameplayRosterReferenceEvidence.requireRetirement(observed);
        queueBoundRetirementTransfers(
            snapshot, observed, lifecycle.get("minimum_retention_millis", Long.class));
      } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_PROOF_INVALID", invalid);
      }
    }
    if (!"RETIRED".equals(lifecycle.get("lifecycle_state", String.class))
        || candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)
        || !Objects.equals(
            candidate.proofDigest(), lifecycle.get("owner_retirement_proof_digest", String.class))
        || !Boolean.TRUE.equals(lifecycle.get("horizon_passed", Boolean.class))
        || unreleasedCount(snapshot) != 0L) {
      return false;
    }
    long disposalEpoch = Math.addExact(candidate.localEpoch(), 1L);
    dsl.execute(
        "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'DISPOSING', local_epoch = ? WHERE snapshot_uuid = ?",
        disposalEpoch,
        snapshot);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_disposal_receipts "
            + "(snapshot_uuid, snapshot_digest, owner_target_fence_uuid, owner_retirement_uuid, owner_retirement_proof_digest, local_epoch, minimum_retention_millis) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        snapshot,
        candidate.binding().snapshot().snapshotDigest(),
        lifecycle.get("owner_target_fence_uuid", UUID.class),
        lifecycle.get("owner_retirement_uuid", UUID.class),
        candidate.proofDigest(),
        disposalEpoch,
        lifecycle.get("minimum_retention_millis", Long.class));
    dsl.execute(
        "UPDATE entity_canonical_roster_admissions SET admission_state = 'DISPOSED', disposed_at = clock_timestamp(), minimum_retention_millis = GREATEST(minimum_retention_millis, ?) "
            + "WHERE snapshot_uuid = ? AND admission_state IN ('OPEN', 'CLOSED')",
        lifecycle.get("minimum_retention_millis", Long.class),
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = ?",
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_retention_references WHERE snapshot_uuid = ?",
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_retention_releases WHERE snapshot_uuid = ?", snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?", snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
        snapshot);
    return true;
  }

  public int purgeDisposedTombstones(int limit) {
    requireOwnerTransaction();
    if (limit <= 0) {
      throw new IllegalArgumentException("Bounded tombstone purge required");
    }
    dsl.execute(
        "DELETE FROM entity_canonical_roster_admissions WHERE operation_uuid IN (SELECT operation_uuid FROM entity_canonical_roster_admissions "
            + "WHERE admission_state = 'DISPOSED' AND disposed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() ORDER BY operation_uuid LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
    return dsl.execute(
        "DELETE FROM entity_canonical_roster_disposal_receipts WHERE snapshot_uuid IN (SELECT snapshot_uuid FROM entity_canonical_roster_disposal_receipts d "
            + "WHERE disposed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() "
            + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.snapshot_uuid = d.snapshot_uuid) "
            + "ORDER BY snapshot_uuid LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
  }

  private void requireNoRetirementTransfer(UUID operation) {
    if (dsl.fetchExists(
        dsl.selectOne()
            .from("entity_canonical_roster_retirement_transfers")
            .where(org.jooq.impl.DSL.field("operation_uuid", UUID.class).eq(operation)))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
  }

  private void lockSnapshotAdmissions(UUID snapshot) {
    dsl.fetch(
        "SELECT admission_uuid FROM entity_canonical_roster_admissions WHERE snapshot_uuid = ? ORDER BY operation_uuid FOR UPDATE",
        snapshot);
  }

  private void queueBoundRetirementTransfers(
      UUID snapshot, GetCanonicalGameplayRosterReferenceResponse proof, long minimum) {
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
    var roster = CanonicalGameplayRosterReferenceEvidence.receipt(proof);
    if (!snapshot.toString().equals(roster.getSnapshotUuid())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_SNAPSHOT_MISMATCH");
    }
    var originals =
        dsl.fetch(
            "SELECT * FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = ? ORDER BY operation_uuid FOR UPDATE",
            snapshot);
    for (Record original : originals) {
      Record admission =
          dsl.fetchOne(
              "SELECT * FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND snapshot_uuid = ? FOR UPDATE",
              original.get("operation_uuid", UUID.class),
              snapshot);
      if (admission == null)
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_ADMISSION_UNAVAILABLE");
      try {
        var request =
            GetCanonicalGameplayRosterReferenceRequest.parseFrom(
                original.get("owner_request", byte[].class));
        queueRetirementTransfer(
            request,
            admission.get("admission_uuid", UUID.class),
            snapshot,
            original.get("snapshot_digest", String.class),
            null,
            original.get("owner_fence", Long.class),
            original.get("owner_target_fence_uuid", UUID.class),
            minimum,
            proof);
      } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_INVALID", invalid);
      }
    }
  }

  private void queueRetirementTransfer(
      GetCanonicalGameplayRosterReferenceRequest original,
      UUID admission,
      UUID snapshot,
      String snapshotDigest,
      CanonicalGameplayRosterRecoveryResponse closed,
      long ownerFence,
      UUID targetFence,
      long minimum,
      GetCanonicalGameplayRosterReferenceResponse proof) {
    CanonicalGameplayRosterReferenceEvidence.requireRequest(original);
    if (closed != null) {
      CanonicalGameplayRosterRecoveryEvidence.requireResponse(closed.getRequest(), closed);
      if (!closed.getRequest().getOriginalSelection().equals(original)
          || !closed.getAdmissionUuid().equals(admission.toString())
          || !closed.getCanonicalRosterReceipt().isEmpty()
          || closed.getOwnerFence() != ownerFence
          || !closed.getTargetFenceUuid().equals(targetFence.toString())) {
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_CLOSURE_MISMATCH");
      }
    }
    Record retained =
        dsl.fetchOne(
            "SELECT * FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ? FOR UPDATE",
            UUID.fromString(original.getOperationUuid()));
    if (retained != null) {
      if (!Arrays.equals(original.toByteArray(), retained.get("original_request", byte[].class))
          || !admission.equals(retained.get("admission_uuid", UUID.class))
          || !Objects.equals(snapshot, retained.get("snapshot_uuid", UUID.class))
          || !Objects.equals(snapshotDigest, retained.get("snapshot_digest", String.class))
          || ownerFence != retained.get("owner_fence", Long.class)
          || !targetFence.equals(retained.get("target_fence_uuid", UUID.class))
          || !Arrays.equals(
              closed == null ? null : closed.toByteArray(),
              retained.get("closed_admission_receipt", byte[].class))) {
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
      }
      if (proof != null
          && "WAITING_RETIREMENT".equals(retained.get("transfer_state", String.class))) {
        promoteRetirementTransfer(retained, proof);
      }
      return;
    }
    UUID observation = UUID.randomUUID();
    var request =
        proof == null
            ? null
            : acknowledgementRequest(
                observation,
                original,
                admission,
                snapshot,
                snapshotDigest,
                ownerFence,
                targetFence,
                minimum,
                proof);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retirement_transfers "
            + "(observation_uuid, operation_uuid, original_request, admission_uuid, snapshot_uuid, snapshot_digest, closed_admission_receipt, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, observed_retirement, acknowledgement_request, request_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        observation,
        UUID.fromString(original.getOperationUuid()),
        original.toByteArray(),
        admission,
        snapshot,
        snapshotDigest,
        closed == null ? null : closed.toByteArray(),
        ownerFence,
        targetFence,
        minimum,
        proof == null ? "WAITING_RETIREMENT" : "PENDING_ACK",
        proof == null ? null : proof.toByteArray(),
        request == null ? null : request.toByteArray(),
        request == null ? null : CanonicalGameplayRosterRetirementEvidence.requestDigest(request));
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgementRequest(
      UUID observation,
      GetCanonicalGameplayRosterReferenceRequest original,
      UUID admission,
      UUID snapshot,
      String digest,
      long ownerFence,
      UUID targetFence,
      long minimum,
      GetCanonicalGameplayRosterReferenceResponse proof) {
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
    if (proof.getOwnerFence() != ownerFence
        || !proof.getTargetFenceUuid().equals(targetFence.toString())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
    }
    var builder =
        CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setObservedRetirement(proof)
            .setObservationUuid(observation.toString())
            .setAdmissionUuid(admission.toString())
            .setMinimumRetentionMillis(minimum);
    if (snapshot != null) builder.setSnapshotUuid(snapshot.toString()).setSnapshotDigest(digest);
    return CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(builder.build());
  }

  public record RetirementTransfer(
      UUID observationUuid,
      String state,
      GetCanonicalGameplayRosterReferenceRequest original,
      CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgementRequest,
      CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgement) {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public GetCanonicalGameplayRosterReferenceRequest original() {
      return original;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgementRequest() {
      return acknowledgementRequest;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgement() {
      return acknowledgement;
    }
  }

  public java.util.List<RetirementTransfer> retirementTransfers(UUID after, int limit) {
    requireOwnerTransaction();
    if (limit <= 0) throw new IllegalArgumentException("Bounded retirement transfer page required");
    return dsl.fetch(
            "SELECT * FROM entity_canonical_roster_retirement_transfers WHERE transfer_state <> 'CONFIRMED' "
                + "AND (?::uuid IS NULL OR observation_uuid > ?::uuid) ORDER BY observation_uuid LIMIT ?",
            after,
            after,
            limit)
        .map(
            row -> {
              try {
                var original =
                    GetCanonicalGameplayRosterReferenceRequest.parseFrom(
                        row.get("original_request", byte[].class));
                CanonicalGameplayRosterReferenceEvidence.requireRequest(original);
                if (!original
                    .getOperationUuid()
                    .equals(row.get("operation_uuid", UUID.class).toString())) {
                  throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
                }
                if (row.get("closed_admission_receipt", byte[].class) != null) {
                  var closed =
                      CanonicalGameplayRosterRecoveryResponse.parseFrom(
                          row.get("closed_admission_receipt", byte[].class));
                  CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                      closed.getRequest(), closed);
                  if (!closed.getRequest().getOriginalSelection().equals(original)
                      || !closed
                          .getAdmissionUuid()
                          .equals(row.get("admission_uuid", UUID.class).toString())
                      || !closed.getCanonicalRosterReceipt().isEmpty()
                      || closed.getOwnerFence() != row.get("owner_fence", Long.class)
                      || !closed
                          .getTargetFenceUuid()
                          .equals(row.get("target_fence_uuid", UUID.class).toString())) {
                    throw new IllegalStateException(
                        "CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
                  }
                }
                var request =
                    row.get("acknowledgement_request", byte[].class) == null
                        ? null
                        : CanonicalGameplayRosterRetirementAcknowledgementRequest.parseFrom(
                            row.get("acknowledgement_request", byte[].class));
                if (request != null) {
                  CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request);
                  if (!request.getOriginalSelection().equals(original)
                      || !request
                          .getObservationUuid()
                          .equals(row.get("observation_uuid", UUID.class).toString())
                      || !request
                          .getAdmissionUuid()
                          .equals(row.get("admission_uuid", UUID.class).toString())
                      || !Objects.equals(
                          request.getSnapshotUuid(),
                          row.get("snapshot_uuid", UUID.class) == null
                              ? ""
                              : row.get("snapshot_uuid", UUID.class).toString())
                      || !Objects.equals(
                          request.getSnapshotDigest(),
                          row.get("snapshot_digest", String.class) == null
                              ? ""
                              : row.get("snapshot_digest", String.class))
                      || request.getMinimumRetentionMillis()
                          != row.get("minimum_retention_millis", Long.class)
                      || request.getObservedRetirement().getOwnerFence()
                          != row.get("owner_fence", Long.class)
                      || !request
                          .getObservedRetirement()
                          .getTargetFenceUuid()
                          .equals(row.get("target_fence_uuid", UUID.class).toString())
                      || !Arrays.equals(
                          request.getObservedRetirement().toByteArray(),
                          row.get("observed_retirement", byte[].class))
                      || !CanonicalGameplayRosterRetirementEvidence.requestDigest(request)
                          .equals(row.get("request_digest", String.class))) {
                    throw new IllegalStateException(
                        "CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
                  }
                }
                var acknowledgement =
                    row.get("acknowledgement_response", byte[].class) == null
                        ? null
                        : CanonicalGameplayRosterRetirementAcknowledgementResponse.parseFrom(
                            row.get("acknowledgement_response", byte[].class));
                if (acknowledgement != null) {
                  CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
                      request, acknowledgement);
                  if (acknowledgement.getMinimumRetentionMillis()
                      != row.get("effective_minimum_retention_millis", Long.class)) {
                    throw new IllegalStateException(
                        "CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
                  }
                }
                return new RetirementTransfer(
                    row.get("observation_uuid", UUID.class),
                    row.get("transfer_state", String.class),
                    original,
                    request,
                    acknowledgement);
              } catch (com.google.protobuf.InvalidProtocolBufferException
                  | IllegalArgumentException invalid) {
                throw new IllegalStateException(
                    "CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID", invalid);
              }
            });
  }

  public boolean observeWaitingRetirement(
      RetirementTransfer candidate, GetCanonicalGameplayRosterReferenceResponse proof) {
    requireOwnerTransaction();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(candidate.original(), proof);
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
    Record retained = lockRetirementTransfer(candidate);
    if (!"WAITING_RETIREMENT".equals(retained.get("transfer_state", String.class))) return false;
    promoteRetirementTransfer(retained, proof);
    return true;
  }

  private void promoteRetirementTransfer(
      Record retained, GetCanonicalGameplayRosterReferenceResponse proof) {
    try {
      var original =
          GetCanonicalGameplayRosterReferenceRequest.parseFrom(
              retained.get("original_request", byte[].class));
      if (retained.get("closed_admission_receipt", byte[].class) != null) {
        var closed =
            CanonicalGameplayRosterRecoveryResponse.parseFrom(
                retained.get("closed_admission_receipt", byte[].class));
        CanonicalGameplayRosterRecoveryEvidence.requireResponse(closed.getRequest(), closed);
        CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(closed.getRequest(), proof);
      }
      var request =
          acknowledgementRequest(
              retained.get("observation_uuid", UUID.class),
              original,
              retained.get("admission_uuid", UUID.class),
              retained.get("snapshot_uuid", UUID.class),
              retained.get("snapshot_digest", String.class),
              retained.get("owner_fence", Long.class),
              retained.get("target_fence_uuid", UUID.class),
              retained.get("minimum_retention_millis", Long.class),
              proof);
      dsl.execute(
          "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'PENDING_ACK', observed_retirement = ?, acknowledgement_request = ?, request_digest = ? WHERE observation_uuid = ?",
          proof.toByteArray(),
          request.toByteArray(),
          CanonicalGameplayRosterRetirementEvidence.requestDigest(request),
          retained.get("observation_uuid", UUID.class));
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID", invalid);
    }
  }

  public boolean recordRetirementAcknowledgement(
      RetirementTransfer candidate,
      CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgement) {
    requireOwnerTransaction();
    CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
        candidate.acknowledgementRequest(), acknowledgement);
    Record retained = lockRetirementTransfer(candidate);
    if (!Arrays.equals(
        candidate.acknowledgementRequest().toByteArray(),
        retained.get("acknowledgement_request", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
    }
    if (!"PENDING_ACK".equals(retained.get("transfer_state", String.class))) {
      if (!Arrays.equals(
          acknowledgement.toByteArray(), retained.get("acknowledgement_response", byte[].class))) {
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
      }
      return false;
    }
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'ACKNOWLEDGED', acknowledgement_response = ?, effective_minimum_retention_millis = ?, acknowledged_at = clock_timestamp() WHERE observation_uuid = ?",
        acknowledgement.toByteArray(),
        acknowledgement.getMinimumRetentionMillis(),
        candidate.observationUuid());
    return true;
  }

  public boolean recordRetirementConfirmation(
      RetirementTransfer candidate,
      CanonicalGameplayRosterRetirementConfirmationResponse response) {
    requireOwnerTransaction();
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            candidate.acknowledgementRequest(), candidate.acknowledgement());
    CanonicalGameplayRosterRetirementEvidence.requireConfirmationResponse(confirmation, response);
    Record retained = lockRetirementTransfer(candidate);
    if (!Arrays.equals(
            candidate.acknowledgementRequest().toByteArray(),
            retained.get("acknowledgement_request", byte[].class))
        || !Arrays.equals(
            candidate.acknowledgement().toByteArray(),
            retained.get("acknowledgement_response", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
    }
    if ("CONFIRMED".equals(retained.get("transfer_state", String.class))) return false;
    if (!"ACKNOWLEDGED".equals(retained.get("transfer_state", String.class)))
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_ACKNOWLEDGEMENT_UNAVAILABLE");
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'CONFIRMED', confirmation_response = ?, confirmed_at = clock_timestamp() WHERE observation_uuid = ?",
        response.toByteArray(),
        candidate.observationUuid());
    return true;
  }

  private Record lockRetirementTransfer(RetirementTransfer candidate) {
    Record retained =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retirement_transfers WHERE observation_uuid = ? FOR UPDATE",
            candidate.observationUuid());
    if (!Arrays.equals(
        candidate.original().toByteArray(), retained.get("original_request", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
    }
    return retained;
  }

  public int purgeConfirmedRetirementTransfers(int limit) {
    requireOwnerTransaction();
    if (limit <= 0)
      throw new IllegalArgumentException("Bounded retirement transfer purge required");
    return dsl.execute(
        "DELETE FROM entity_canonical_roster_retirement_transfers WHERE observation_uuid IN "
            + "(SELECT observation_uuid FROM entity_canonical_roster_retirement_transfers WHERE transfer_state = 'CONFIRMED' "
            + "AND confirmed_at + GREATEST(minimum_retention_millis, effective_minimum_retention_millis) * interval '1 millisecond' <= clock_timestamp() "
            + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = entity_canonical_roster_retirement_transfers.operation_uuid) "
            + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.operation_uuid = entity_canonical_roster_retirement_transfers.operation_uuid) "
            + "ORDER BY observation_uuid LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
  }

  private long unreleasedCount(UUID snapshot) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM entity_canonical_roster_retention_references r WHERE r.snapshot_uuid = ? "
                + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_releases x WHERE x.reference_uuid = r.reference_uuid)",
            snapshot)
        .get(0, Long.class);
  }

  public enum ReferenceKind {
    ORIGINAL_SELECTION,
    RECOVERY,
    HOLD
  }

  /** An exact acquire operation remains replayable after its matching release. */
  public void acquire(
      CanonicalGameplayRosterRetentionBinding binding,
      UUID operationUuid,
      UUID referenceUuid,
      ReferenceKind kind) {
    requireUuid(operationUuid, "operationUuid");
    requireUuid(referenceUuid, "referenceUuid");
    Objects.requireNonNull(kind, "kind");
    lock(binding);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retention_references "
            + "(reference_uuid, acquire_operation_uuid, snapshot_uuid, snapshot_digest, reference_kind) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        referenceUuid,
        operationUuid,
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        kind.name());
    Record retained =
        dsl.fetchOne(
            "SELECT acquire_operation_uuid, snapshot_uuid, snapshot_digest, reference_kind "
                + "FROM entity_canonical_roster_retention_references WHERE reference_uuid = ?",
            referenceUuid);
    if (retained == null
        || !operationUuid.equals(retained.get("acquire_operation_uuid", UUID.class))
        || !binding.snapshot().snapshotUuid().equals(retained.get("snapshot_uuid", UUID.class))
        || !binding
            .snapshot()
            .snapshotDigest()
            .equals(retained.get("snapshot_digest", String.class))
        || !kind.name().equals(retained.get("reference_kind", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_REFERENCE_IDEMPOTENCY_CONFLICT");
    }
  }

  /** A release is an immutable exact operation result, never an expiry inferred from age. */
  public void release(
      CanonicalGameplayRosterRetentionBinding binding, UUID operationUuid, UUID referenceUuid) {
    requireUuid(operationUuid, "operationUuid");
    requireUuid(referenceUuid, "referenceUuid");
    lock(binding);
    if (dsl.fetchExists(
        dsl.selectOne()
            .from("entity_canonical_roster_original_selections")
            .where(org.jooq.impl.DSL.field("operation_uuid", UUID.class).eq(referenceUuid)))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RELEASE_AUTHORITY_UNAVAILABLE");
    }
    Record acquisition =
        dsl.fetchOne(
            "SELECT 1 FROM entity_canonical_roster_retention_references "
                + "WHERE reference_uuid = ? AND snapshot_uuid = ? AND snapshot_digest = ?",
            referenceUuid,
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest());
    if (acquisition == null) {
      throw new IllegalStateException("CANONICAL_ROSTER_REFERENCE_UNAVAILABLE");
    }
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retention_releases "
            + "(release_operation_uuid, reference_uuid, snapshot_uuid, snapshot_digest) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        operationUuid,
        referenceUuid,
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest());
    Record retained =
        dsl.fetchOne(
            "SELECT release_operation_uuid, snapshot_uuid, snapshot_digest "
                + "FROM entity_canonical_roster_retention_releases WHERE reference_uuid = ?",
            referenceUuid);
    if (retained == null
        || !operationUuid.equals(retained.get("release_operation_uuid", UUID.class))
        || !binding.snapshot().snapshotUuid().equals(retained.get("snapshot_uuid", UUID.class))
        || !binding
            .snapshot()
            .snapshotDigest()
            .equals(retained.get("snapshot_digest", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RELEASE_IDEMPOTENCY_CONFLICT");
    }
  }

  /** No caller can turn zero references into terminal retirement on the current evidence wire. */
  public Protection protection(CanonicalGameplayRosterRetentionBinding binding) {
    lock(binding);
    Record counts =
        dsl.fetchSingle(
            "SELECT count(*) FILTER (WHERE r.reference_kind <> 'HOLD') AS live_references, "
                + "count(*) FILTER (WHERE r.reference_kind = 'HOLD') AS holds "
                + "FROM entity_canonical_roster_retention_references r "
                + "WHERE r.snapshot_uuid = ? AND NOT EXISTS "
                + "(SELECT 1 FROM entity_canonical_roster_retention_releases x "
                + "WHERE x.reference_uuid = r.reference_uuid)",
            binding.snapshot().snapshotUuid());
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT lifecycle_state, retirement_observed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
            binding.snapshot().snapshotUuid());
    long references = counts.get("live_references", Long.class);
    long holds = counts.get("holds", Long.class);
    boolean retired = "RETIRED".equals(lifecycle.get("lifecycle_state", String.class));
    return new Protection(
        references,
        holds,
        retired
            && references == 0L
            && holds == 0L
            && Boolean.TRUE.equals(lifecycle.get("horizon_passed", Boolean.class)),
        retired ? "AUTHENTICATED_TARGET_RETIRED" : "AUTHENTICATED_SUPERSESSION_UNAVAILABLE");
  }

  public record Protection(
      long liveReferences, long holds, boolean disposalEligible, String terminality) {}

  public record Health(long oldestBlockingSeconds, long cleanupLagSeconds) {}

  public Health health() {
    requireOwnerTransaction();
    Record row =
        dsl.fetchSingle(
            "SELECT COALESCE(max(extract(epoch FROM clock_timestamp() - recorded_at)) FILTER (WHERE lifecycle_state = 'PROTECTED'), 0)::bigint AS oldest_blocking, "
                + "COALESCE(max(GREATEST(0, extract(epoch FROM clock_timestamp() - retirement_observed_at - minimum_retention_millis * interval '1 millisecond'))) "
                + "FILTER (WHERE lifecycle_state = 'RETIRED'), 0)::bigint AS cleanup_lag FROM entity_canonical_roster_retention_lifecycle");
    return new Health(row.get("oldest_blocking", Long.class), row.get("cleanup_lag", Long.class));
  }

  private void lock(CanonicalGameplayRosterRetentionBinding binding) {
    requireOwnerTransaction();
    Objects.requireNonNull(binding, "binding");
    var target = binding.target();
    Record exact =
        dsl.fetchOne(
            "SELECT snapshot_uuid FROM entity_canonical_gameplay_roster_snapshots "
                + "WHERE snapshot_uuid = ? AND snapshot_digest = ? AND canonical_account_uuid = ? "
                + "AND tenant_uuid = ? AND realm_uuid = ? AND world_slug = ? AND realm_slug = ? "
                + "AND game_instance_uuid = ? AND catalog_revision = ? AND canonical_version_uuid = ? "
                + "AND pointer_version = ? AND active_world_epoch = ? "
                + "AND published_policy_digest = ? AND published_release_bundle_ref = ? "
                + "AND admission_pointer_snapshot_digest = ? AND published_owner_proof_digest = ? "
                + "AND playable_state_namespace_uuid = ? AND playable_state_scope = ? "
                + "AND entry_policy = ? AND construction_state = 'SEALED' "
                + "AND publication_evidence_format = 'CANONICAL_UUID_V2' FOR UPDATE",
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            binding.canonicalAccountUuid(),
            target.tenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.pointerVersion(),
            target.activeWorldEpoch(),
            target.publishedPolicyDigest(),
            target.publishedReleaseBundleRef(),
            target.admissionPointerSnapshotDigest(),
            target.publishedOwnerProofDigest(),
            target.playableStateNamespaceId(),
            target.playableStateScope().name(),
            target.entryPolicy().name());
    if (exact == null) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    }
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retention_lifecycle "
            + "(snapshot_uuid, snapshot_digest) VALUES (?, ?) ON CONFLICT DO NOTHING",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest());
    dsl.fetchSingle(
        "SELECT snapshot_uuid FROM entity_canonical_roster_retention_lifecycle "
            + "WHERE snapshot_uuid = ? AND snapshot_digest = ? FOR UPDATE",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest());
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_OWNER_TRANSACTION_REQUIRED");
    }
  }

  private static void requireUuid(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be non-nil");
    }
  }
}
