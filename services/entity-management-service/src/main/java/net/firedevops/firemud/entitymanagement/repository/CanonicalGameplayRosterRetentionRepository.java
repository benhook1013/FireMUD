package net.firedevops.firemud.entitymanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest;
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
  // Admission bindings omit namespace; this valid placeholder only reconstructs comparison data.
  private static final String CLASSIFICATION_ONLY_NAMESPACE = "retained-classification";

  private final DSLContext dsl;

  public CanonicalGameplayRosterRetentionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  public UUID reserveAdmission(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    return reserveAdmission(
        request.requestUuid(), admissionBinding(request), "PRESEEDED_ONLY", targetBinding(request));
  }

  public UUID reserveAdmission(CanonicalPlayerCreatedRosterRequest request) {
    CanonicalPlayerCreatedRosterEvidence.requireRequest(request);
    return reserveAdmission(
        UUID.fromString(request.getRequestUuid()),
        admissionBinding(request),
        "PLAYER_CREATED",
        targetBinding(request));
  }

  private UUID reserveAdmission(UUID operation, byte[] binding, String policy, byte[] target) {
    requireOwnerTransaction();
    requireNoRetirementTransfer(operation);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding, entry_policy) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        operation,
        UUID.randomUUID(),
        binding,
        policy);
    Record original =
        dsl.fetchSingle(
            "SELECT admission_uuid, request_binding, admission_state, entry_policy FROM entity_canonical_roster_admissions WHERE operation_uuid = ? FOR UPDATE",
            operation);
    classifySource("ADMISSION", operation, original.get("admission_uuid", UUID.class));
    requireUnsealedTarget(target);
    requireNoRetirementTransfer(operation);
    if (!Arrays.equals(binding, original.get("request_binding", byte[].class))
        || !policy.equals(original.get("entry_policy", String.class))) {
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
    requireAdmission(
        request.requestUuid(),
        nonce,
        admissionBinding(request),
        "PRESEEDED_ONLY",
        targetBinding(request));
  }

  public void requireAdmission(CanonicalPlayerCreatedRosterRequest request, UUID nonce) {
    CanonicalPlayerCreatedRosterEvidence.requireRequest(request);
    requireAdmission(
        UUID.fromString(request.getRequestUuid()),
        nonce,
        admissionBinding(request),
        "PLAYER_CREATED",
        targetBinding(request));
  }

  private void requireAdmission(
      UUID operation, UUID nonce, byte[] binding, String policy, byte[] target) {
    requireOwnerTransaction();
    Record original =
        dsl.fetchOne(
            "SELECT request_binding, entry_policy FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ? AND admission_state = 'OPEN' FOR UPDATE",
            operation,
            nonce);
    classifySource("ADMISSION", operation, nonce);
    requireUnsealedTarget(target);
    requireNoRetirementTransfer(operation);
    if (original == null
        || !Arrays.equals(binding, original.get("request_binding", byte[].class))
        || !policy.equals(original.get("entry_policy", String.class))) {
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
    if (request.expectedTarget().entryPolicy()
        != net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy
            .PRESEEDED_ONLY) {
      throw new IllegalArgumentException("PRESEEDED_ONLY admission request required");
    }
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

  private static byte[] admissionBinding(CanonicalPlayerCreatedRosterRequest request) {
    return boundedBinding(
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(request.getRequestUuid())
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setExpectedTarget(request.getExpectedTarget())
            .setExpectedSource(request.getExpectedSource())
            .setPlayerExecutionContext(
                net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                    .setSessionId(request.getPlayerExecutionContext().getSessionId()))
            .build()
            .toByteArray());
  }

  private static byte[] boundedBinding(byte[] bytes) {
    if (bytes.length > 4096)
      throw new IllegalArgumentException("Original owner binding exceeds 4096 bytes");
    return bytes;
  }

  private static boolean playerCreated(GetCanonicalGameplayRosterReferenceRequest request) {
    return CanonicalGameplayRosterReferenceEvidence.target(request).getEntryPolicy()
        == net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
            .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED;
  }

  private static String entryPolicy(GetCanonicalGameplayRosterReferenceRequest request) {
    return playerCreated(request) ? "PLAYER_CREATED" : "PRESEEDED_ONLY";
  }

  private static byte[] admissionBinding(GetCanonicalGameplayRosterReferenceRequest request) {
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    if (playerCreated(request)) {
      return admissionBinding(
          CanonicalPlayerCreatedRosterRequest.newBuilder()
              .setRequestUuid(request.getOperationUuid())
              .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
              .setExpectedTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
              .setExpectedSource(
                  CanonicalGameplayRosterReferenceEvidence.playerCreatedSourceIdentity(request))
              .setPlayerExecutionContext(
                  net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                      .setSessionId(request.getSessionUuid()))
              .build());
    }
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
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding, entry_policy) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        operation,
        UUID.randomUUID(),
        binding,
        entryPolicy(original));
    Record admission =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_admissions WHERE operation_uuid = ? FOR UPDATE",
            operation);
    if (!Arrays.equals(binding, admission.get("request_binding", byte[].class))
        || !entryPolicy(original).equals(admission.get("entry_policy", String.class))) {
      throw new RecoveryConflictException("CANONICAL_ROSTER_LOCAL_ADMISSION_REQUEST_CONFLICT");
    }
    classifySource("ADMISSION", operation, admission.get("admission_uuid", UUID.class));
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
        if ((!owner.getCanonicalRosterReceipt().isEmpty()
                && !owner.getCanonicalRosterReceipt().equals(retained.getCanonicalRosterReceipt()))
            || (!owner.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
                && !owner
                    .getCanonicalPlayerCreatedRosterReceipt()
                    .equals(retained.getCanonicalPlayerCreatedRosterReceipt()))) {
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
    var createdReceipt =
        playerCreated(original) ? originalPlayerCreatedReceipt(owner).orElse(null) : null;
    var receipt =
        playerCreated(original)
            ? (createdReceipt == null ? null : createdReceipt.getRoster())
            : originalReceipt(owner).orElse(null);
    UUID snapshot = admission.get("snapshot_uuid", UUID.class);
    if ((snapshot == null) != (receipt == null)
        || (snapshot != null && !snapshot.toString().equals(receipt.getSnapshotUuid()))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_LOCAL_RECEIPT_MISMATCH");
    }
    var response =
        playerCreated(original)
            ? CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
                request,
                admission.get("admission_uuid", UUID.class),
                owner.getOwnerFence(),
                UUID.fromString(owner.getTargetFenceUuid()),
                createdReceipt)
            : CanonicalGameplayRosterRecoveryEvidence.response(
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
      UUID operationUuid,
      UUID admissionUuid,
      CanonicalGameplayRosterRecoveryResponse response,
      boolean valid) {
    public ClosedAdmissionCandidate(
        UUID operationUuid, UUID admissionUuid, CanonicalGameplayRosterRecoveryResponse response) {
      this(operationUuid, admissionUuid, response, true);
    }

    public static ClosedAdmissionCandidate invalid(UUID operationUuid, UUID admissionUuid) {
      return new ClosedAdmissionCandidate(operationUuid, admissionUuid, null, false);
    }

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
                byte[] bytes = row.get("recovery_response", byte[].class);
                if (bytes == null)
                  return ClosedAdmissionCandidate.invalid(
                      row.get("operation_uuid", UUID.class), row.get("admission_uuid", UUID.class));
                var response = CanonicalGameplayRosterRecoveryResponse.parseFrom(bytes);
                CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                    response.getRequest(), response);
                return new ClosedAdmissionCandidate(
                    row.get("operation_uuid", UUID.class),
                    row.get("admission_uuid", UUID.class),
                    response);
              } catch (com.google.protobuf.InvalidProtocolBufferException
                  | IllegalArgumentException
                  | IllegalStateException invalid) {
                return ClosedAdmissionCandidate.invalid(
                    row.get("operation_uuid", UUID.class), row.get("admission_uuid", UUID.class));
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
    if (!Boolean.TRUE.equals(admission.get("horizon_passed", Boolean.class))) return false;
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
    if (playerCreated(request))
      throw new IllegalArgumentException("PRESEEDED_ONLY original receipt required");
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

  public Optional<CanonicalPlayerCreatedRosterResponse> originalPlayerCreatedReceipt(
      GetCanonicalGameplayRosterReferenceResponse owner) {
    requireOwnerTransaction();
    var request = owner.getRequest();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, owner);
    if (!playerCreated(request))
      throw new IllegalArgumentException("PLAYER_CREATED original receipt required");
    UUID operation = UUID.fromString(request.getOperationUuid());
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "entity-roster-original:" + operation);
    Record retained =
        dsl.fetchOne(
            "SELECT * FROM entity_canonical_roster_original_selections WHERE operation_uuid = ? FOR UPDATE",
            operation);
    if (retained == null) return Optional.empty();
    if (!Arrays.equals(request.toByteArray(), retained.get("owner_request", byte[].class))
        || owner.getOwnerFence() != retained.get("owner_fence", Long.class)
        || !owner
            .getTargetFenceUuid()
            .equals(retained.get("owner_target_fence_uuid", UUID.class).toString())
        || !"PLAYER_CREATED".equals(retained.get("entry_policy", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    }
    try {
      var receipt =
          CanonicalPlayerCreatedRosterResponse.parseFrom(
              retained.get("canonical_player_created_roster_receipt", byte[].class));
      CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
          request, owner.getOwnerFence(), UUID.fromString(owner.getTargetFenceUuid()), receipt);
      if (!owner.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
          && !owner.getCanonicalPlayerCreatedRosterReceipt().equals(receipt.toByteString())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
      }
      return Optional.of(receipt);
    } catch (com.google.protobuf.InvalidProtocolBufferException
        | IllegalArgumentException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_INVALID", invalid);
    }
  }

  /**
   * Joins capture's owner transaction and retains the full created source, never a preseeded
   * receipt.
   */
  public void bindPlayerCreatedOriginal(
      GetCanonicalGameplayRosterReferenceResponse owner,
      CanonicalPlayerCreatedRosterResponse receipt) {
    requireOwnerTransaction();
    var request = owner.getRequest();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, owner);
    CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
        request, owner.getOwnerFence(), UUID.fromString(owner.getTargetFenceUuid()), receipt);
    UUID operation = UUID.fromString(request.getOperationUuid());
    Record admission =
        dsl.fetchOne(
            "SELECT * FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_state = 'OPEN' FOR UPDATE",
            operation);
    requireNoRetirementTransfer(operation);
    if (admission == null
        || !"PLAYER_CREATED".equals(admission.get("entry_policy", String.class))
        || !Arrays.equals(admissionBinding(request), admission.get("request_binding", byte[].class))
        || (admission.get("snapshot_uuid", UUID.class) != null
            && !admission
                .get("snapshot_uuid", UUID.class)
                .toString()
                .equals(receipt.getRoster().getSnapshotUuid()))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
    var roster = receipt.getRoster();
    var binding =
        new CanonicalGameplayRosterRetentionBinding(
            UUID.fromString(roster.getCanonicalAccountUuid()),
            new net.firedevops.firemud.entitymanagement.service
                .CanonicalGameplayRosterSnapshotReference(
                UUID.fromString(roster.getSnapshotUuid()), roster.getSnapshotDigest()),
            net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget.fromWire(
                roster.getTarget()));
    initializeSnapshotTarget(binding.snapshot().snapshotUuid());
    requireUnsealedTarget(targetBinding(request));
    lock(binding);
    var source =
        dsl.fetchSingle(
            "SELECT canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
            binding.snapshot().snapshotUuid());
    if (!Arrays.equals(
        request.getCanonicalPlayerCreatedSourceIdentity().toByteArray(),
        source.get(0, byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    }
    acquire(binding, operation, operation, ReferenceKind.ORIGINAL_SELECTION);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_original_selections "
            + "(operation_uuid, owner_request, owner_fence, owner_target_fence_uuid, snapshot_uuid, snapshot_digest, canonical_player_created_roster_receipt, entry_policy) VALUES (?, ?, ?, ?, ?, ?, ?, 'PLAYER_CREATED')",
        operation,
        request.toByteArray(),
        owner.getOwnerFence(),
        UUID.fromString(owner.getTargetFenceUuid()),
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        receipt.toByteArray());
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
      String proofDigest,
      UUID snapshotUuid,
      boolean valid) {
    public Candidate(
        CanonicalGameplayRosterRetentionBinding binding,
        GetCanonicalGameplayRosterReferenceRequest ownerRequest,
        long localEpoch,
        String lifecycleState,
        String proofDigest) {
      this(
          binding,
          ownerRequest,
          localEpoch,
          lifecycleState,
          proofDigest,
          binding.snapshot().snapshotUuid(),
          true);
    }

    public static Candidate invalid(UUID snapshotUuid) {
      return new Candidate(null, null, 0, null, null, snapshotUuid, false);
    }

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
                byte[] bytes = row.get("owner_request", byte[].class);
                if (bytes == null) return Candidate.invalid(row.get("snapshot_uuid", UUID.class));
                var request =
                    net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                        .parseFrom(bytes);
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
              } catch (com.google.protobuf.InvalidProtocolBufferException
                  | IllegalArgumentException
                  | IllegalStateException invalid) {
                return Candidate.invalid(row.get("snapshot_uuid", UUID.class));
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
    var receipt = CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(proof);
    if (!candidate.binding().snapshot().snapshotUuid().toString().equals(receipt.getSnapshotUuid())
        || !candidate.binding().snapshot().snapshotDigest().equals(receipt.getSnapshotDigest())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_SNAPSHOT_MISMATCH");
    }
    requireOwnerTransaction();
    // Capture holds its nonce before this target fence. Sealing never locks every nonce;
    // delayed captures recheck this durable seal before any snapshot access.
    byte[] targetBinding = targetBinding(candidate.ownerRequest());
    Record sealed =
        dsl.fetchOne(
            "SELECT * FROM entity_canonical_roster_retired_targets WHERE target_digest = ? AND snapshot_uuid = ? AND snapshot_digest = ? AND target_binding = ?",
            targetDigest(targetBinding),
            candidate.binding().snapshot().snapshotUuid(),
            candidate.binding().snapshot().snapshotDigest(),
            targetBinding);
    if (sealed != null) return sameRetirementSeal(candidate, proof, sealed);
    lockTarget(targetBinding);
    // Recheck this snapshot after waiting. A sibling seal fences acquisition but does not
    // replace this snapshot's independently authenticated proof and original inventory.
    sealed =
        dsl.fetchOne(
            "SELECT * FROM entity_canonical_roster_retired_targets WHERE target_digest = ? AND snapshot_uuid = ? AND snapshot_digest = ? AND target_binding = ?",
            targetDigest(targetBinding),
            candidate.binding().snapshot().snapshotUuid(),
            candidate.binding().snapshot().snapshotDigest(),
            targetBinding);
    if (sealed != null) return sameRetirementSeal(candidate, proof, sealed);
    requireCompatibleTargetSeal(targetBinding, proof);
    lock(candidate.binding());
    UUID snapshot = candidate.binding().snapshot().snapshotUuid();
    initializeSnapshotTarget(snapshot);
    if (!Arrays.equals(
        snapshotTargetBinding(candidate.binding()), targetBinding(candidate.ownerRequest())))
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            snapshot);
    if (candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)) return false;
    if (!"PROTECTED".equals(lifecycle.get("lifecycle_state", String.class))) {
      return Arrays.equals(
          proof.toByteArray(), lifecycle.get("owner_retirement_proof", byte[].class));
    }
    Record anchor =
        dsl.fetchOne(
            "SELECT o.owner_request, o.owner_fence, o.owner_target_fence_uuid, o.entry_policy FROM entity_canonical_roster_original_selections o "
                + "JOIN entity_canonical_roster_admissions a USING (operation_uuid, snapshot_uuid) "
                + "WHERE o.operation_uuid = ? AND o.snapshot_uuid = ? AND o.snapshot_digest = ?",
            UUID.fromString(candidate.ownerRequest().getOperationUuid()),
            snapshot,
            candidate.binding().snapshot().snapshotDigest());
    if (anchor == null || !qualifiedOriginal(anchor, proof)) return false;
    Long originalCount = lifecycle.get("original_selection_count", Long.class);
    if (originalCount == null || originalCount <= 0) return false;
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retired_targets (target_digest, target_binding, snapshot_uuid, snapshot_digest, owner_retirement_proof, minimum_retention_millis) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        targetDigest(targetBinding),
        targetBinding,
        snapshot,
        candidate.binding().snapshot().snapshotDigest(),
        proof.toByteArray(),
        policy.minimumRetention().toMillis());
    Record seal =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retired_targets WHERE target_digest = ? AND snapshot_uuid = ? AND snapshot_digest = ? AND target_binding = ?",
            targetDigest(targetBinding),
            snapshot,
            candidate.binding().snapshot().snapshotDigest(),
            targetBinding);
    if (!Arrays.equals(targetBinding, seal.get("target_binding", byte[].class))
        || !snapshot.equals(seal.get("snapshot_uuid", UUID.class))
        || !Arrays.equals(proof.toByteArray(), seal.get("owner_retirement_proof", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TARGET_CONFLICT");
    }
    dsl.execute(
        "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'RETIRED', local_epoch = local_epoch + 1, "
            + "owner_target_fence_uuid = ?, owner_retirement_uuid = ?, owner_retirement_proof_digest = ?, owner_retirement_proof = ?, "
            + "retirement_observed_at = clock_timestamp(), minimum_retention_millis = ?, retirement_original_count = ? WHERE snapshot_uuid = ? AND lifecycle_state = 'PROTECTED'",
        UUID.fromString(proof.getTargetFenceUuid()),
        UUID.fromString(proof.getRetirementUuid()),
        proof.getRetirementProofDigest(),
        proof.toByteArray(),
        policy.minimumRetention().toMillis(),
        originalCount,
        candidate.binding().snapshot().snapshotUuid());
    return true;
  }

  private void requireCompatibleTargetSeal(
      byte[] target, GetCanonicalGameplayRosterReferenceResponse proof) {
    if (!Arrays.equals(target, targetBinding(proof.getRequest()))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TARGET_CONFLICT");
    }
    Record sibling =
        dsl.fetchOne(
            "SELECT target_binding, owner_retirement_proof FROM entity_canonical_roster_retired_targets WHERE target_digest = ? ORDER BY snapshot_uuid LIMIT 1",
            targetDigest(target));
    if (sibling == null) return;
    byte[] retainedBytes = sibling.get("owner_retirement_proof", byte[].class);
    if (retainedBytes == null
        || !Arrays.equals(target, sibling.get("target_binding", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TARGET_CONFLICT");
    }
    try {
      var retained = GetCanonicalGameplayRosterReferenceResponse.parseFrom(retainedBytes);
      CanonicalGameplayRosterReferenceEvidence.requireResponse(retained.getRequest(), retained);
      CanonicalGameplayRosterReferenceEvidence.requireRetirement(retained);
      if (!Arrays.equals(target, targetBinding(retained.getRequest()))
          || retained.getOwnerFence() != proof.getOwnerFence()
          || !retained.getTargetFenceUuid().equals(proof.getTargetFenceUuid())
          || !retained.getRetirementUuid().equals(proof.getRetirementUuid())
          || !retained.getSupersedingPointerDigest().equals(proof.getSupersedingPointerDigest())
          || retained.getSupersedingPointerVersion() != proof.getSupersedingPointerVersion()
          || retained.getSupersedingCatalogRevision() != proof.getSupersedingCatalogRevision()
          || retained.getSupersedingWorldEpoch() != proof.getSupersedingWorldEpoch()) {
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TARGET_CONFLICT");
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TARGET_CONFLICT", invalid);
    }
  }

  private boolean sameRetirementSeal(
      Candidate candidate, GetCanonicalGameplayRosterReferenceResponse proof, Record seal) {
    Record epoch =
        dsl.fetchOne(
            "SELECT local_epoch FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
            candidate.binding().snapshot().snapshotUuid());
    return epoch != null
        && candidate.localEpoch() == epoch.get("local_epoch", Long.class)
        && candidate
            .binding()
            .snapshot()
            .snapshotUuid()
            .equals(seal.get("snapshot_uuid", UUID.class))
        && Arrays.equals(proof.toByteArray(), seal.get("owner_retirement_proof", byte[].class));
  }

  /** All deletes are guarded by exact persisted owner proof, local epoch and current references. */
  public boolean dispose(Candidate candidate) {
    requireOwnerTransaction();
    lockPreparation(candidate.binding().snapshot().snapshotUuid());
    lock(candidate.binding());
    UUID snapshot = candidate.binding().snapshot().snapshotUuid();
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT *, retirement_observed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            snapshot);
    if (!"RETIRED".equals(lifecycle.get("lifecycle_state", String.class))
        || candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)
        || !Objects.equals(
            candidate.proofDigest(), lifecycle.get("owner_retirement_proof_digest", String.class))
        || !Boolean.TRUE.equals(lifecycle.get("horizon_passed", Boolean.class))
        || hasUnreleasedReference(snapshot)
        || !preparationComplete(snapshot, lifecycle)) {
      return false;
    }
    retainedRetirement(candidate.binding(), lifecycle);
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
        "UPDATE entity_canonical_roster_retired_targets SET payload_disposed_at = clock_timestamp() WHERE snapshot_uuid = ? AND snapshot_digest = ? AND owner_retirement_proof = ?",
        snapshot,
        candidate.binding().snapshot().snapshotDigest(),
        lifecycle.get("owner_retirement_proof", byte[].class));
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

  /** Additive classification only: original admissions, transfer bytes and xmin never change. */
  public int classifyRetainedDependencies(int limit) {
    requireOwnerTransaction();
    if (limit <= 0)
      throw new IllegalArgumentException("Bounded retained classification page required");
    dsl.fetchSingle("SELECT entity_roster_initialize_snapshot_targets(?)", limit);
    int createdSnapshots = initializeCreatedSnapshotTargets(null, limit);
    initializeDisposedSealTargets(limit);
    var pending =
        dsl.fetch(
            "SELECT q.* FROM (SELECT 'ADMISSION' AS source_kind, operation_uuid, admission_uuid, entry_policy FROM entity_canonical_roster_admissions "
                + "UNION ALL SELECT 'TRANSFER', operation_uuid, admission_uuid, entry_policy FROM entity_canonical_roster_retirement_transfers "
                + "UNION ALL SELECT 'TRANSFER_RETIREMENT', operation_uuid, admission_uuid, entry_policy FROM entity_canonical_roster_retirement_transfers WHERE transfer_state <> 'WAITING_RETIREMENT') q "
                + "WHERE NOT EXISTS (SELECT 1 FROM entity_canonical_roster_target_classifications m WHERE m.source_kind = q.source_kind "
                + "AND m.operation_uuid = q.operation_uuid AND m.admission_uuid = q.admission_uuid "
                + "AND m.normalization_version = CASE WHEN q.entry_policy = 'PLAYER_CREATED' THEN 2 ELSE 1 END) "
                + "ORDER BY q.source_kind, q.operation_uuid, q.admission_uuid LIMIT ?",
            limit);
    for (Record source : pending)
      classifySource(
          source.get("source_kind", String.class),
          source.get("operation_uuid", UUID.class),
          source.get("admission_uuid", UUID.class));
    return createdSnapshots + pending.size();
  }

  private void initializeSnapshotTarget(UUID snapshot) {
    dsl.execute(
        "INSERT INTO entity_canonical_roster_snapshot_targets (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_snapshot_projection) "
            + "SELECT snapshot_uuid, snapshot_digest, 'SNAPSHOT', CASE WHEN entity_roster_snapshot_target_known(s) THEN 'KNOWN' ELSE 'UNKNOWN' END, "
            + "CASE WHEN entity_roster_snapshot_target_known(s) THEN entity_roster_snapshot_target_projection(s) ELSE NULL END, entity_roster_snapshot_target_projection(s) "
            + "FROM entity_canonical_gameplay_roster_snapshots s WHERE snapshot_uuid = ? AND construction_state = 'SEALED' ON CONFLICT DO NOTHING",
        snapshot);
    initializeCreatedSnapshotTargets(snapshot, 1);
  }

  private int initializeCreatedSnapshotTargets(UUID snapshot, int limit) {
    var pending =
        dsl.fetch(
            "SELECT m.*, (jsonb_populate_record(NULL::entity_canonical_gameplay_roster_snapshots, m.source_snapshot_projection)).canonical_player_created_source_identity AS retained_source "
                + "FROM entity_canonical_roster_snapshot_targets m WHERE m.source_kind = 'SNAPSHOT' AND m.recognition_version = 1 "
                + "AND m.source_snapshot_projection->>'entry_policy' = 'PLAYER_CREATED' AND (?::uuid IS NULL OR m.snapshot_uuid = ?::uuid) "
                + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_snapshot_targets typed WHERE typed.snapshot_uuid = m.snapshot_uuid AND typed.source_kind = m.source_kind AND typed.recognition_version = 2) "
                + "ORDER BY m.snapshot_uuid LIMIT ?",
            snapshot,
            snapshot,
            limit);
    for (Record source : pending) {
      boolean known = false;
      byte[] bytes = source.get("retained_source", byte[].class);
      try {
        if (bytes == null) throw new IllegalArgumentException("Missing created snapshot source");
        var identity =
            net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity
                .parseFrom(bytes);
        CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(identity);
        known =
            Arrays.equals(bytes, identity.toByteArray())
                && "KNOWN".equals(source.get("classification_state", String.class));
      } catch (com.google.protobuf.InvalidProtocolBufferException
          | IllegalArgumentException invalid) {
        // Exact raw inventory stays UNKNOWN; later source rows still get a bounded turn.
      }
      dsl.execute(
          "INSERT INTO entity_canonical_roster_snapshot_targets (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_snapshot_projection, recognition_version) VALUES (?, ?, 'SNAPSHOT', ?, ?::jsonb, ?::jsonb, 2) ON CONFLICT DO NOTHING",
          source.get("snapshot_uuid", UUID.class),
          source.get("snapshot_digest", String.class),
          known ? "KNOWN" : "UNKNOWN",
          known ? source.get("target_projection", org.jooq.JSONB.class) : null,
          source.get("source_snapshot_projection", org.jooq.JSONB.class));
    }
    return pending.size();
  }

  /** Missing payload supplies no authority: only an already guarded positive disposed seal does. */
  private void initializeDisposedSealTargets(int limit) {
    var seals =
        dsl.fetch(
            "SELECT s.* FROM entity_canonical_roster_retired_targets s WHERE payload_disposed_at IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM entity_roster_effective_snapshot_targets m WHERE m.snapshot_uuid = s.snapshot_uuid AND m.source_kind = 'DISPOSED_SEAL' AND m.classification_state = 'KNOWN' AND m.source_retirement_proof = s.owner_retirement_proof) "
                + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_snapshot_targets m WHERE m.snapshot_uuid = s.snapshot_uuid AND m.source_kind = 'DISPOSED_SEAL' AND m.recognition_version = 2) "
                + "ORDER BY s.snapshot_uuid LIMIT ?",
            limit);
    for (Record seal : seals) {
      UUID account = null;
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget target = null;
      byte[] bytes = seal.get("owner_retirement_proof", byte[].class);
      byte[] createdSource = null;
      int recognition = 2;
      try {
        if (bytes == null) throw new IllegalArgumentException("Missing retained retirement proof");
        var proof = GetCanonicalGameplayRosterReferenceResponse.parseFrom(bytes);
        CanonicalGameplayRosterReferenceEvidence.requireResponse(proof.getRequest(), proof);
        CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
        var receipt = CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(proof);
        if (!receipt.getSnapshotUuid().equals(seal.get("snapshot_uuid", UUID.class).toString())
            || !receipt.getSnapshotDigest().equals(seal.get("snapshot_digest", String.class))
            || !Arrays.equals(
                targetBinding(proof.getRequest()), seal.get("target_binding", byte[].class))
            || !targetDigest(targetBinding(proof.getRequest()))
                .equals(seal.get("target_digest", String.class))) {
          throw new IllegalArgumentException("Retained snapshot seal binding mismatch");
        }
        var checked =
            net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget.fromWire(
                CanonicalGameplayRosterReferenceEvidence.target(proof.getRequest()));
        if (playerCreated(proof.getRequest())) {
          var identity =
              CanonicalGameplayRosterReferenceEvidence.playerCreatedSourceIdentity(
                  proof.getRequest());
          CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(identity);
          createdSource = identity.toByteArray();
        } else recognition = 1;
        account = UUID.fromString(proof.getRequest().getCanonicalAccountUuid());
        target = checked;
      } catch (com.google.protobuf.InvalidProtocolBufferException
          | IllegalArgumentException
          | IllegalStateException invalid) {
        // Explicit immutable UNKNOWN classification passes later rows without authorizing cleanup.
        recognition = 2;
      }
      org.jooq.JSONB projection = null;
      if (target != null) {
        projection =
            dsl.fetchSingle(
                    "SELECT entity_roster_target_projection(?::uuid, ?::uuid, ?::uuid, ?::text, ?::text, ?::uuid, ?::bigint, ?::uuid, ?::bigint, ?::bigint, ?::text, ?::text, ?::text, ?::text, ?::uuid, ?::text, ?::text, ?::bytea, 'CANONICAL_UUID_V2'::text)",
                    account,
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
                    target.entryPolicy().name(),
                    createdSource)
                .get(0, org.jooq.JSONB.class);
      }
      dsl.execute(
          "INSERT INTO entity_canonical_roster_snapshot_targets (snapshot_uuid, snapshot_digest, source_kind, classification_state, target_projection, source_retirement_proof, recognition_version) "
              + "VALUES (?, ?, 'DISPOSED_SEAL', ?, ?::jsonb, ?, ?) ON CONFLICT DO NOTHING",
          seal.get("snapshot_uuid", UUID.class),
          seal.get("snapshot_digest", String.class),
          projection == null ? "UNKNOWN" : "KNOWN",
          projection,
          bytes,
          recognition);
    }
  }

  private void classifySource(String kind, UUID operation, UUID admission) {
    boolean admissionSource = "ADMISSION".equals(kind);
    Record source =
        dsl.fetchOne(
            "SELECT * FROM "
                + (admissionSource
                    ? "entity_canonical_roster_admissions"
                    : "entity_canonical_roster_retirement_transfers")
                + " WHERE operation_uuid = ? AND admission_uuid = ?",
            operation,
            admission);
    if (source == null) return;
    byte[] original =
        source.get(admissionSource ? "request_binding" : "original_request", byte[].class);
    byte[] target = null;
    byte[] proofBytes = null;
    try {
      if (admissionSource && "PLAYER_CREATED".equals(source.get("entry_policy", String.class))) {
        var minimal = CanonicalPlayerCreatedRosterRequest.parseFrom(original);
        var checked =
            CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
                operation,
                UUID.fromString(minimal.getCanonicalAccountUuid()),
                UUID.fromString(minimal.getPlayerExecutionContext().getSessionId()),
                CLASSIFICATION_ONLY_NAMESPACE,
                minimal.getExpectedTarget(),
                minimal.getExpectedSource());
        if (!operation.toString().equals(minimal.getRequestUuid())
            || !Arrays.equals(original, admissionBinding(checked)))
          throw new IllegalArgumentException("Retained created admission binding is not canonical");
        target = targetBinding(checked);
      } else if (admissionSource) {
        var request =
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.parseFrom(
                original);
        var canonicalTarget =
            net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget.fromWire(
                request.getExpectedTarget());
        var checked =
            CanonicalGameplayRosterReferenceEvidence.request(
                CanonicalGameplayRosterReferenceEvidence.uuid(request.getRequestUuid()),
                CanonicalGameplayRosterReferenceEvidence.uuid(request.getCanonicalAccountUuid()),
                CanonicalGameplayRosterReferenceEvidence.uuid(
                    request.getPlayerExecutionContext().getSessionId()),
                CLASSIFICATION_ONLY_NAMESPACE,
                canonicalTarget.toWire());
        if (!operation.toString().equals(request.getRequestUuid())
            || !Arrays.equals(original, admissionBinding(checked))) {
          throw new IllegalArgumentException("Retained admission binding is not canonical");
        }
        target = targetBinding(checked);
      } else {
        var request = GetCanonicalGameplayRosterReferenceRequest.parseFrom(original);
        CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
        if (!operation.toString().equals(request.getOperationUuid())
            || !entryPolicy(request).equals(source.get("entry_policy", String.class)))
          throw new IllegalArgumentException("Retained operation mismatch");
        target = targetBinding(request);
        if ("TRANSFER_RETIREMENT".equals(kind)) {
          byte[] observedProof = source.get("observed_retirement", byte[].class);
          byte[] retainedAcknowledgement = source.get("acknowledgement_request", byte[].class);
          if (observedProof == null || retainedAcknowledgement == null) {
            throw new IllegalArgumentException("Retained transfer lacks positive proof or intent");
          }
          var proof = GetCanonicalGameplayRosterReferenceResponse.parseFrom(observedProof);
          CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
          var expected =
              acknowledgementRequest(
                  source.get("observation_uuid", UUID.class),
                  request,
                  admission,
                  source.get("snapshot_uuid", UUID.class),
                  source.get("snapshot_digest", String.class),
                  source.get("owner_fence", Long.class),
                  source.get("target_fence_uuid", UUID.class),
                  source.get("minimum_retention_millis", Long.class),
                  proof);
          var retained =
              net.firedevops.firemud.gamesession.v1
                  .CanonicalGameplayRosterRetirementAcknowledgementRequest.parseFrom(
                  retainedAcknowledgement);
          if (!expected.equals(retained)
              || !CanonicalGameplayRosterRetirementEvidence.requestDigest(expected)
                  .equals(source.get("request_digest", String.class))) {
            throw new IllegalArgumentException("Retained transfer proof binding mismatch");
          }
          if (source.get("closed_admission_receipt", byte[].class) != null) {
            var closed =
                CanonicalGameplayRosterRecoveryResponse.parseFrom(
                    source.get("closed_admission_receipt", byte[].class));
            CanonicalGameplayRosterRecoveryEvidence.requireResponse(closed.getRequest(), closed);
            CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(
                closed.getRequest(), proof);
            if (!closed.getAdmissionUuid().equals(admission.toString()))
              throw new IllegalArgumentException("Retained closure nonce mismatch");
          }
          Record local =
              dsl.fetchOne(
                  "SELECT request_binding FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ?",
                  operation,
                  admission);
          if (local != null
              && !Arrays.equals(
                  admissionBinding(request), local.get("request_binding", byte[].class))) {
            throw new IllegalArgumentException("Retained transfer admission binding mismatch");
          }
          proofBytes = proof.toByteArray();
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException
        | IllegalArgumentException
        | IllegalStateException invalid) {
      // Explicit unknown metadata is durable, holds authority closed, and lets later rows advance.
      target = null;
      proofBytes = null;
    }
    dsl.execute(
        "INSERT INTO entity_canonical_roster_target_classifications "
            + "(source_kind, operation_uuid, admission_uuid, source_binding, classification_state, target_binding, target_digest, retirement_proof, normalization_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        kind,
        operation,
        admission,
        original,
        target == null ? "UNKNOWN" : "KNOWN",
        target,
        target == null ? null : targetDigest(target),
        proofBytes,
        "PLAYER_CREATED".equals(source.get("entry_policy", String.class)) ? 2 : 1);
  }

  private boolean hasUnknownTargetDependency() {
    return Boolean.TRUE.equals(
        dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
            .get(0, Boolean.class));
  }

  private boolean hasRetiredTransfer(byte[] target) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("entity_roster_effective_target_classifications m")
            .join("entity_canonical_roster_retirement_transfers t")
            .on(
                "m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid AND m.source_binding = t.original_request")
            .where(
                "m.source_kind = 'TRANSFER_RETIREMENT' AND m.classification_state = 'KNOWN' AND m.target_digest = ? "
                    + "AND m.target_binding = ? AND m.retirement_proof = t.observed_retirement AND t.transfer_state <> 'WAITING_RETIREMENT'",
                targetDigest(target),
                target));
  }

  public int purgeTargetClassifications(int limit) {
    requireOwnerTransaction();
    if (limit <= 0) throw new IllegalArgumentException("Bounded classification purge required");
    dsl.execute(
        "DELETE FROM entity_canonical_roster_snapshot_targets WHERE (snapshot_uuid, source_kind, recognition_version) IN "
            + "(SELECT snapshot_uuid, source_kind, recognition_version FROM entity_canonical_roster_snapshot_targets WHERE NOT entity_roster_snapshot_target_dependency(snapshot_uuid) "
            + "ORDER BY snapshot_uuid, source_kind, recognition_version LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
    return dsl.execute(
        "DELETE FROM entity_canonical_roster_target_classifications WHERE (source_kind, operation_uuid, admission_uuid, normalization_version) IN "
            + "(SELECT m.source_kind, m.operation_uuid, m.admission_uuid, m.normalization_version FROM entity_canonical_roster_target_classifications m "
            + "WHERE NOT entity_roster_classification_source_exists(m.source_kind, m.operation_uuid, m.admission_uuid) "
            + "ORDER BY m.source_kind, m.operation_uuid, m.admission_uuid, m.normalization_version LIMIT ? FOR UPDATE SKIP LOCKED)",
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

  /**
   * One stable original page. RETIRED is the seal, not completion. Missing/invalid rows stay
   * protected; the durable cursor passes them so another original can advance on a later page.
   */
  public int prepareRetirement(Candidate candidate, int limit) {
    requireOwnerTransaction();
    if (limit <= 0)
      throw new IllegalArgumentException("Bounded retirement preparation page required");
    UUID snapshot = candidate.binding().snapshot().snapshotUuid();
    lockPreparation(snapshot);
    Record progress =
        dsl.fetchOne(
            "SELECT retirement_preparation_cursor, retirement_prepared_count, retirement_original_count FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? AND lifecycle_state = 'RETIRED'",
            snapshot);
    if (progress == null) return 0;
    // This is only a no-more-preparation optimization. Disposal independently proves every
    // actual original/nonce/release/intent and the immutable positive sealed inventory.
    if (Objects.equals(
        progress.get("retirement_original_count", Long.class),
        progress.get("retirement_prepared_count", Long.class))) return 0;
    UUID after = progress.get("retirement_preparation_cursor", UUID.class);
    var operations =
        dsl.fetch(
                "SELECT operation_uuid FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = ? "
                    + "AND (?::uuid IS NULL OR operation_uuid > ?::uuid) "
                    + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retirement_transfers t "
                    + "JOIN entity_canonical_roster_admissions a USING (operation_uuid, admission_uuid) "
                    + "JOIN entity_canonical_roster_retention_releases x ON x.reference_uuid = t.operation_uuid "
                    + "WHERE t.operation_uuid = entity_canonical_roster_original_selections.operation_uuid "
                    + "AND t.snapshot_uuid = entity_canonical_roster_original_selections.snapshot_uuid "
                    + "AND t.snapshot_digest = entity_canonical_roster_original_selections.snapshot_digest "
                    + "AND t.original_request = entity_canonical_roster_original_selections.owner_request "
                    + "AND t.transfer_state <> 'WAITING_RETIREMENT' AND x.snapshot_uuid = t.snapshot_uuid AND x.snapshot_digest = t.snapshot_digest) "
                    + "ORDER BY operation_uuid LIMIT ?",
                snapshot,
                after,
                after,
                limit)
            .getValues(0, UUID.class);
    if (operations.isEmpty() && after == null) return 0;
    // Admission -> snapshot -> lifecycle, bounded by this page, never by total fan-out.
    if (!operations.isEmpty())
      dsl.fetch(
          "SELECT admission_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ANY(?::uuid[]) ORDER BY operation_uuid FOR UPDATE",
          (Object) operations.toArray(UUID[]::new));
    lock(candidate.binding());
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            snapshot);
    if (!"RETIRED".equals(lifecycle.get("lifecycle_state", String.class))
        || candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)
        || !Objects.equals(
            candidate.proofDigest(), lifecycle.get("owner_retirement_proof_digest", String.class))
        || !Objects.equals(after, lifecycle.get("retirement_preparation_cursor", UUID.class)))
      return 0;
    var proof = retainedRetirement(candidate.binding(), lifecycle);
    int prepared = 0;
    for (UUID operation : operations) {
      Record original =
          dsl.fetchOne(
              "SELECT o.*, a.admission_uuid, a.request_binding, a.admission_state FROM entity_canonical_roster_original_selections o "
                  + "LEFT JOIN entity_canonical_roster_admissions a USING (operation_uuid, snapshot_uuid) "
                  + "WHERE o.operation_uuid = ? AND o.snapshot_uuid = ? FOR UPDATE OF o",
              operation,
              snapshot);
      if (original == null
          || original.get("admission_uuid", UUID.class) == null
          || !java.util.Set.of("OPEN", "CLOSED")
              .contains(original.get("admission_state", String.class))
          || !candidate
              .binding()
              .snapshot()
              .snapshotDigest()
              .equals(original.get("snapshot_digest", String.class))
          || !qualifiedOriginal(original, proof)) continue;
      GetCanonicalGameplayRosterReferenceRequest request;
      String receiptDigest;
      try {
        request =
            GetCanonicalGameplayRosterReferenceRequest.parseFrom(
                original.get("owner_request", byte[].class));
        if (!Arrays.equals(
            admissionBinding(request), original.get("request_binding", byte[].class))) continue;
        var receipt = originalSnapshotReceipt(original, request);
        if (!receipt.getSnapshotUuid().equals(snapshot.toString())
            || !receipt.getSnapshotDigest().equals(candidate.binding().snapshot().snapshotDigest()))
          continue;
        receiptDigest = receipt.getSnapshotDigest();
      } catch (com.google.protobuf.InvalidProtocolBufferException
          | IllegalArgumentException invalid) {
        // Corrupt/mismatched evidence remains incomplete. Other originals still get their turn.
        continue;
      }
      // Resolve retained binding conflicts before creating any intent or release for this original.
      Record transfer =
          dsl.fetchOne(
              "SELECT * FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ? FOR UPDATE",
              operation);
      if (transfer != null
          && !transferBindingMatches(
              transfer,
              request,
              original.get("admission_uuid", UUID.class),
              snapshot,
              receiptDigest,
              null,
              original.get("owner_fence", Long.class),
              original.get("owner_target_fence_uuid", UUID.class))) continue;
      if (transfer != null && !preparedTransferMatches(transfer, proof)) continue;
      // A missing acquisition is unknown, never a release or completion.
      if (!dsl.fetchExists(
          dsl.selectOne()
              .from("entity_canonical_roster_retention_references")
              .where(
                  "reference_uuid = ? AND acquire_operation_uuid = ? AND snapshot_uuid = ? AND snapshot_digest = ? AND reference_kind = 'ORIGINAL_SELECTION'",
                  operation,
                  operation,
                  snapshot,
                  receiptDigest))) continue;
      queueRetirementTransfer(
          request,
          original.get("admission_uuid", UUID.class),
          snapshot,
          receiptDigest,
          null,
          original.get("owner_fence", Long.class),
          original.get("owner_target_fence_uuid", UUID.class),
          lifecycle.get("minimum_retention_millis", Long.class),
          proof);
      UUID release =
          UUID.nameUUIDFromBytes(
              (proof.getRetirementUuid() + ":" + operation)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      dsl.execute(
          "INSERT INTO entity_canonical_roster_retention_releases (release_operation_uuid, reference_uuid, snapshot_uuid, snapshot_digest) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
          release,
          operation,
          snapshot,
          receiptDigest);
      prepared++;
    }
    dsl.execute(
        "UPDATE entity_canonical_roster_retention_lifecycle SET retirement_preparation_cursor = ?, retirement_prepared_count = retirement_prepared_count + ?, local_epoch = local_epoch + 1 WHERE snapshot_uuid = ?",
        operations.isEmpty() ? null : operations.getLast(),
        prepared,
        snapshot);
    return prepared;
  }

  private static CanonicalGameplayRosterResponse originalSnapshotReceipt(
      Record original, GetCanonicalGameplayRosterReferenceRequest request)
      throws com.google.protobuf.InvalidProtocolBufferException {
    if (!entryPolicy(request).equals(original.get("entry_policy", String.class)))
      throw new IllegalArgumentException("Original receipt policy mismatch");
    if (playerCreated(request)) {
      byte[] bytes = original.get("canonical_player_created_roster_receipt", byte[].class);
      if (bytes == null || original.get("canonical_roster_receipt", byte[].class) != null)
        throw new IllegalArgumentException("Exclusive created receipt required");
      var receipt = CanonicalPlayerCreatedRosterResponse.parseFrom(bytes);
      CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
          request,
          original.get("owner_fence", Long.class),
          original.get("owner_target_fence_uuid", UUID.class),
          receipt);
      return receipt.getRoster();
    }
    byte[] bytes = original.get("canonical_roster_receipt", byte[].class);
    if (bytes == null
        || original.get("canonical_player_created_roster_receipt", byte[].class) != null)
      throw new IllegalArgumentException("Exclusive preseeded receipt required");
    var receipt = CanonicalGameplayRosterResponse.parseFrom(bytes);
    CanonicalGameplayRosterReferenceEvidence.response(
        request,
        original.get("owner_fence", Long.class),
        original.get("owner_target_fence_uuid", UUID.class),
        receipt);
    return receipt;
  }

  private boolean qualifiedOriginal(
      Record original, GetCanonicalGameplayRosterReferenceResponse proof) {
    try {
      var request =
          GetCanonicalGameplayRosterReferenceRequest.parseFrom(
              original.get("owner_request", byte[].class));
      CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
      return original.get("owner_fence", Long.class) == proof.getOwnerFence()
          && original
              .get("owner_target_fence_uuid", UUID.class)
              .toString()
              .equals(proof.getTargetFenceUuid())
          && entryPolicy(request).equals(original.get("entry_policy", String.class))
          && request.getTargetNamespace().equals(proof.getRequest().getTargetNamespace())
          && Arrays.equals(targetBinding(request), targetBinding(proof.getRequest()));
    } catch (com.google.protobuf.InvalidProtocolBufferException
        | IllegalArgumentException invalid) {
      return false;
    }
  }

  private GetCanonicalGameplayRosterReferenceResponse retainedRetirement(
      CanonicalGameplayRosterRetentionBinding binding, Record lifecycle) {
    var proof = validatedRetainedProof(binding, lifecycle);
    Record seal =
        dsl.fetchOne(
            "SELECT * FROM entity_canonical_roster_retired_targets WHERE target_digest = ? AND snapshot_uuid = ? AND snapshot_digest = ? AND target_binding = ?",
            targetDigest(snapshotTargetBinding(binding)),
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            snapshotTargetBinding(binding));
    if (seal == null
        || !Arrays.equals(snapshotTargetBinding(binding), seal.get("target_binding", byte[].class))
        || !Arrays.equals(proof.toByteArray(), seal.get("owner_retirement_proof", byte[].class))
        || !binding.snapshot().snapshotUuid().equals(seal.get("snapshot_uuid", UUID.class))
        || !binding.snapshot().snapshotDigest().equals(seal.get("snapshot_digest", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_PROOF_INVALID");
    }
    return proof;
  }

  private GetCanonicalGameplayRosterReferenceResponse validatedRetainedProof(
      CanonicalGameplayRosterRetentionBinding binding, Record lifecycle) {
    try {
      var proof =
          GetCanonicalGameplayRosterReferenceResponse.parseFrom(
              lifecycle.get("owner_retirement_proof", byte[].class));
      CanonicalGameplayRosterReferenceEvidence.requireResponse(proof.getRequest(), proof);
      CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
      var receipt = CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(proof);
      if (!entryPolicy(proof.getRequest()).equals(lifecycle.get("entry_policy", String.class))
          || !receipt.getSnapshotUuid().equals(binding.snapshot().snapshotUuid().toString())
          || !receipt.getSnapshotDigest().equals(binding.snapshot().snapshotDigest())
          || !Arrays.equals(snapshotTargetBinding(binding), targetBinding(proof.getRequest()))
          || !proof
              .getRetirementProofDigest()
              .equals(lifecycle.get("owner_retirement_proof_digest", String.class))
          || !proof
              .getTargetFenceUuid()
              .equals(String.valueOf(lifecycle.get("owner_target_fence_uuid", UUID.class)))
          || !proof
              .getRetirementUuid()
              .equals(String.valueOf(lifecycle.get("owner_retirement_uuid", UUID.class)))) {
        throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_PROOF_INVALID");
      }
      return proof;
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_PROOF_INVALID", invalid);
    }
  }

  /** Reconstructs only already-retained authenticated retirement, never terminality from counts. */
  public boolean initializeRetainedRetirement(Candidate candidate) {
    requireOwnerTransaction();
    UUID snapshot = candidate.binding().snapshot().snapshotUuid();
    lockPreparation(snapshot);
    lockTarget(targetBinding(candidate.ownerRequest()));
    lock(candidate.binding());
    initializeSnapshotTarget(snapshot);
    if (!Arrays.equals(
        snapshotTargetBinding(candidate.binding()), targetBinding(candidate.ownerRequest())))
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            snapshot);
    if (!"RETIRED".equals(lifecycle.get("lifecycle_state", String.class))
        || candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)
        || lifecycle.get("retirement_original_count", Long.class) == null) return false;
    var proof = validatedRetainedProof(candidate.binding(), lifecycle);
    requireCompatibleTargetSeal(targetBinding(candidate.ownerRequest()), proof);
    Record anchor =
        dsl.fetchOne(
            "SELECT o.* FROM entity_canonical_roster_original_selections o "
                + "JOIN entity_canonical_roster_admissions a USING (operation_uuid, snapshot_uuid) "
                + "WHERE o.operation_uuid = ? AND o.snapshot_uuid = ? AND a.admission_state IN ('OPEN', 'CLOSED')",
            UUID.fromString(proof.getRequest().getOperationUuid()),
            snapshot);
    if (anchor == null
        || !qualifiedOriginal(anchor, proof)
        || !Arrays.equals(
            anchor.get("owner_request", byte[].class), proof.getRequest().toByteArray())
        || !Arrays.equals(
            anchor.get(
                playerCreated(proof.getRequest())
                    ? "canonical_player_created_roster_receipt"
                    : "canonical_roster_receipt",
                byte[].class),
            playerCreated(proof.getRequest())
                ? proof.getCanonicalPlayerCreatedRosterReceipt().toByteArray()
                : proof.getCanonicalRosterReceipt().toByteArray())) return false;
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retired_targets (target_digest, target_binding, snapshot_uuid, snapshot_digest, owner_retirement_proof, minimum_retention_millis) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        targetDigest(targetBinding(candidate.ownerRequest())),
        targetBinding(candidate.ownerRequest()),
        snapshot,
        candidate.binding().snapshot().snapshotDigest(),
        proof.toByteArray(),
        lifecycle.get("minimum_retention_millis", Long.class));
    retainedRetirement(candidate.binding(), lifecycle);
    return true;
  }

  private boolean preparationComplete(UUID snapshot, Record lifecycle) {
    Long expected = lifecycle.get("retirement_original_count", Long.class);
    if (expected == null || expected <= 0) return false;
    Record completion =
        dsl.fetchSingle(
            "SELECT count(*) AS originals, count(*) FILTER (WHERE a.admission_uuid IS NOT NULL "
                + "AND a.admission_state IN ('OPEN', 'CLOSED') AND a.entry_policy = o.entry_policy AND t.entry_policy = o.entry_policy "
                + "AND o.entry_policy = l.entry_policy AND t.admission_uuid = a.admission_uuid "
                + "AND t.original_request = o.owner_request AND t.snapshot_uuid = o.snapshot_uuid AND t.snapshot_digest = o.snapshot_digest "
                + "AND t.owner_fence = o.owner_fence AND t.target_fence_uuid = o.owner_target_fence_uuid "
                + "AND t.observed_retirement = ? AND t.transfer_state <> 'WAITING_RETIREMENT' "
                + "AND t.acknowledgement_request IS NOT NULL AND m.classification_state = 'KNOWN' "
                + "AND m.source_binding = t.original_request AND m.retirement_proof = t.observed_retirement "
                + "AND r.reference_kind = 'ORIGINAL_SELECTION' "
                + "AND r.acquire_operation_uuid = o.operation_uuid AND r.snapshot_uuid = o.snapshot_uuid AND r.snapshot_digest = o.snapshot_digest "
                + "AND x.reference_uuid = o.operation_uuid "
                + "AND x.snapshot_uuid = o.snapshot_uuid AND x.snapshot_digest = o.snapshot_digest) AS prepared "
                + "FROM entity_canonical_roster_original_selections o JOIN entity_canonical_roster_retention_lifecycle l USING (snapshot_uuid, snapshot_digest) LEFT JOIN entity_canonical_roster_admissions a USING (operation_uuid, snapshot_uuid) "
                + "LEFT JOIN entity_canonical_roster_retirement_transfers t USING (operation_uuid) "
                + "LEFT JOIN entity_roster_effective_target_classifications m ON m.source_kind = 'TRANSFER_RETIREMENT' AND m.operation_uuid = t.operation_uuid AND m.admission_uuid = t.admission_uuid "
                + "LEFT JOIN entity_canonical_roster_retention_references r ON r.reference_uuid = o.operation_uuid "
                + "LEFT JOIN entity_canonical_roster_retention_releases x ON x.reference_uuid = o.operation_uuid WHERE o.snapshot_uuid = ?",
            lifecycle.get("owner_retirement_proof", byte[].class),
            snapshot);
    return expected.equals(completion.get("originals", Long.class))
        && expected.equals(completion.get("prepared", Long.class));
  }

  private static byte[] targetBinding(CanonicalGameplayRosterRetentionBinding binding) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setCanonicalAccountUuid(binding.canonicalAccountUuid().toString())
        .setExpectedTarget(binding.target().toWire())
        .build()
        .toByteArray();
  }

  private static byte[] targetBinding(GetCanonicalGameplayRosterReferenceRequest request) {
    CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
    if (playerCreated(request))
      return targetBinding(
          CanonicalPlayerCreatedRosterRequest.newBuilder()
              .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
              .setExpectedTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
              .setExpectedSource(
                  CanonicalGameplayRosterReferenceEvidence.playerCreatedSourceIdentity(request))
              .build());
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setExpectedTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
        .build()
        .toByteArray();
  }

  private static byte[] targetBinding(CanonicalPlayerCreatedRosterRequest request) {
    CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(request.getExpectedSource());
    if (request.getExpectedTarget().getEntryPolicy()
        != net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
            .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
      throw new IllegalArgumentException("Created target required");
    return boundedBinding(
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setExpectedTarget(request.getExpectedTarget())
            .setExpectedSource(request.getExpectedSource())
            .build()
            .toByteArray());
  }

  private byte[] snapshotTargetBinding(CanonicalGameplayRosterRetentionBinding binding) {
    if (binding.target().entryPolicy()
        != net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy
            .PLAYER_CREATED) return targetBinding(binding);
    Record source =
        dsl.fetchOne(
            "SELECT canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ? AND snapshot_digest = ?",
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest());
    byte[] bytes = source == null ? null : source.get(0, byte[].class);
    if (bytes == null)
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    try {
      var identity =
          net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity
              .parseFrom(bytes);
      CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(identity);
      if (!Arrays.equals(identity.toByteArray(), bytes))
        throw new IllegalArgumentException("Noncanonical created source");
      return targetBinding(
          CanonicalPlayerCreatedRosterRequest.newBuilder()
              .setCanonicalAccountUuid(binding.canonicalAccountUuid().toString())
              .setExpectedTarget(binding.target().toWire())
              .setExpectedSource(identity)
              .build());
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH", invalid);
    }
  }

  private static byte[] targetBinding(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setExpectedTarget(request.expectedTarget().toWire())
        .build()
        .toByteArray();
  }

  private static String targetDigest(byte[] binding) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(binding));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void lockPreparation(UUID snapshot) {
    // Prevent a page selected before completion from taking admission locks while final
    // disposal owns the snapshot. Both retain bounded preparation and one final transaction.
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "entity-roster-retirement:" + snapshot);
  }

  private void lockTarget(byte[] binding) {
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "entity-roster-target:" + targetDigest(binding));
  }

  void requireUnsealedTarget(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    requireUnsealedTarget(targetBinding(request));
  }

  private void requireUnsealedTarget(byte[] target) {
    requireOwnerTransaction();
    lockTarget(target);
    if (hasUnknownTargetDependency()
        || hasRetiredTransfer(target)
        || dsl.fetchExists(
            dsl.selectOne()
                .from("entity_canonical_roster_retired_targets")
                .where("target_digest = ?", targetDigest(target)))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
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
          || !closed.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
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
      if (!transferBindingMatches(
          retained,
          original,
          admission,
          snapshot,
          snapshotDigest,
          closed,
          ownerFence,
          targetFence)) {
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
            + "(observation_uuid, operation_uuid, original_request, admission_uuid, snapshot_uuid, snapshot_digest, closed_admission_receipt, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, observed_retirement, acknowledgement_request, request_digest, entry_policy) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
        request == null ? null : CanonicalGameplayRosterRetirementEvidence.requestDigest(request),
        entryPolicy(original));
    classifySource("TRANSFER", UUID.fromString(original.getOperationUuid()), admission);
    if (proof != null)
      classifySource(
          "TRANSFER_RETIREMENT", UUID.fromString(original.getOperationUuid()), admission);
  }

  private static boolean transferBindingMatches(
      Record retained,
      GetCanonicalGameplayRosterReferenceRequest original,
      UUID admission,
      UUID snapshot,
      String snapshotDigest,
      CanonicalGameplayRosterRecoveryResponse closed,
      long ownerFence,
      UUID targetFence) {
    return Arrays.equals(original.toByteArray(), retained.get("original_request", byte[].class))
        && admission.equals(retained.get("admission_uuid", UUID.class))
        && entryPolicy(original).equals(retained.get("entry_policy", String.class))
        && Objects.equals(snapshot, retained.get("snapshot_uuid", UUID.class))
        && Objects.equals(snapshotDigest, retained.get("snapshot_digest", String.class))
        && ownerFence == retained.get("owner_fence", Long.class)
        && targetFence.equals(retained.get("target_fence_uuid", UUID.class))
        && Arrays.equals(
            closed == null ? null : closed.toByteArray(),
            retained.get("closed_admission_receipt", byte[].class));
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
      CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgement,
      boolean valid) {
    public RetirementTransfer(
        UUID observationUuid,
        String state,
        GetCanonicalGameplayRosterReferenceRequest original,
        CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgementRequest,
        CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgement) {
      this(observationUuid, state, original, acknowledgementRequest, acknowledgement, true);
    }

    public static RetirementTransfer invalid(UUID observationUuid, String state) {
      return new RetirementTransfer(observationUuid, state, null, null, null, false);
    }

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
                byte[] bytes = row.get("original_request", byte[].class);
                if (bytes == null)
                  return RetirementTransfer.invalid(
                      row.get("observation_uuid", UUID.class),
                      row.get("transfer_state", String.class));
                var original = GetCanonicalGameplayRosterReferenceRequest.parseFrom(bytes);
                CanonicalGameplayRosterReferenceEvidence.requireRequest(original);
                if (!entryPolicy(original).equals(row.get("entry_policy", String.class)))
                  throw new IllegalArgumentException("Transfer policy mismatch");
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
                      || !closed.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
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
                var acknowledgement =
                    row.get("acknowledgement_response", byte[].class) == null
                        ? null
                        : CanonicalGameplayRosterRetirementAcknowledgementResponse.parseFrom(
                            row.get("acknowledgement_response", byte[].class));
                if ((acknowledgement != null && request == null)
                    || (request == null
                        && !"WAITING_RETIREMENT".equals(row.get("transfer_state", String.class)))
                    || (acknowledgement == null
                        && "ACKNOWLEDGED".equals(row.get("transfer_state", String.class)))) {
                  throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID");
                }
                if (request != null) {
                  // Each fresh retained row is fully validated once. The guarded response
                  // validator also computes and binds this same immutable request digest.
                  String requestDigest =
                      acknowledgement == null
                          ? CanonicalGameplayRosterRetirementEvidence.requestDigest(request)
                          : CanonicalGameplayRosterRetirementEvidence
                              .requireAcknowledgementResponse(request, acknowledgement)
                              .getRequestDigest();
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
                      || !requestDigest.equals(row.get("request_digest", String.class))) {
                    throw new IllegalStateException(
                        "CANONICAL_ROSTER_RETIREMENT_TRANSFER_CONFLICT");
                  }
                }
                if (acknowledgement != null) {
                  Long effectiveMinimum = row.get("effective_minimum_retention_millis", Long.class);
                  if (effectiveMinimum == null
                      || acknowledgement.getMinimumRetentionMillis() != effectiveMinimum) {
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
                  | IllegalArgumentException
                  | IllegalStateException invalid) {
                return RetirementTransfer.invalid(
                    row.get("observation_uuid", UUID.class),
                    row.get("transfer_state", String.class));
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

  /** Pure retained-evidence qualification; no original effects may precede this check. */
  private static boolean preparedTransferMatches(
      Record retained, GetCanonicalGameplayRosterReferenceResponse proof) {
    try {
      var expected = retirementPromotionRequest(retained, proof);
      if ("WAITING_RETIREMENT".equals(retained.get("transfer_state", String.class))) {
        return retained.get("observed_retirement", byte[].class) == null
            && retained.get("acknowledgement_request", byte[].class) == null
            && retained.get("request_digest", String.class) == null;
      }
      byte[] observed = retained.get("observed_retirement", byte[].class);
      byte[] intent = retained.get("acknowledgement_request", byte[].class);
      if (observed == null || intent == null || !Arrays.equals(observed, proof.toByteArray())) {
        return false;
      }
      var actual = CanonicalGameplayRosterRetirementAcknowledgementRequest.parseFrom(intent);
      CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(actual);
      return actual.equals(expected)
          && CanonicalGameplayRosterRetirementEvidence.requestDigest(actual)
              .equals(retained.get("request_digest", String.class));
    } catch (com.google.protobuf.InvalidProtocolBufferException
        | IllegalArgumentException
        | IllegalStateException invalid) {
      return false;
    }
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest retirementPromotionRequest(
      Record retained, GetCanonicalGameplayRosterReferenceResponse proof)
      throws com.google.protobuf.InvalidProtocolBufferException {
    byte[] bytes = retained.get("original_request", byte[].class);
    UUID observation = retained.get("observation_uuid", UUID.class);
    UUID admission = retained.get("admission_uuid", UUID.class);
    UUID targetFence = retained.get("target_fence_uuid", UUID.class);
    Long ownerFence = retained.get("owner_fence", Long.class);
    Long minimum = retained.get("minimum_retention_millis", Long.class);
    if (bytes == null
        || observation == null
        || admission == null
        || targetFence == null
        || ownerFence == null
        || minimum == null) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID");
    }
    var original = GetCanonicalGameplayRosterReferenceRequest.parseFrom(bytes);
    CanonicalGameplayRosterReferenceEvidence.requireRequest(original);
    if (!entryPolicy(original).equals(retained.get("entry_policy", String.class)))
      throw new IllegalArgumentException("Transfer policy mismatch");
    byte[] closedBytes = retained.get("closed_admission_receipt", byte[].class);
    if (closedBytes != null) {
      var closed = CanonicalGameplayRosterRecoveryResponse.parseFrom(closedBytes);
      CanonicalGameplayRosterRecoveryEvidence.requireResponse(closed.getRequest(), closed);
      CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(closed.getRequest(), proof);
      if (!closed.getRequest().getOriginalSelection().equals(original)
          || !closed.getAdmissionUuid().equals(admission.toString())
          || !closed.getCanonicalRosterReceipt().isEmpty()
          || !closed.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
          || closed.getOwnerFence() != ownerFence
          || !closed.getTargetFenceUuid().equals(targetFence.toString())) {
        throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID");
      }
    }
    UUID snapshot = retained.get("snapshot_uuid", UUID.class);
    String digest = retained.get("snapshot_digest", String.class);
    if (snapshot == null ? digest != null || closedBytes == null : digest == null) {
      throw new IllegalArgumentException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID");
    }
    return acknowledgementRequest(
        observation,
        original,
        admission,
        snapshot,
        digest,
        ownerFence,
        targetFence,
        minimum,
        proof);
  }

  private void promoteRetirementTransfer(
      Record retained, GetCanonicalGameplayRosterReferenceResponse proof) {
    final CanonicalGameplayRosterRetirementAcknowledgementRequest request;
    try {
      request = retirementPromotionRequest(retained, proof);
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_INVALID", invalid);
    }
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'PENDING_ACK', observed_retirement = ?, acknowledgement_request = ?, request_digest = ? WHERE observation_uuid = ?",
        proof.toByteArray(),
        request.toByteArray(),
        CanonicalGameplayRosterRetirementEvidence.requestDigest(request),
        retained.get("observation_uuid", UUID.class));
    classifySource(
        "TRANSFER_RETIREMENT",
        UUID.fromString(request.getOriginalSelection().getOperationUuid()),
        retained.get("admission_uuid", UUID.class));
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
    // The response guard validates the complete confirmation and nested proof once below;
    // constructing the immutable envelope supplies no validation or retirement authority.
    var confirmation =
        CanonicalGameplayRosterRetirementConfirmationRequest.newBuilder()
            .setSchemaVersion(1)
            .setAcknowledgementRequest(candidate.acknowledgementRequest())
            .setAcknowledgement(candidate.acknowledgement())
            .build();
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

  /** Finite compact seals outlive every positively classified pre-seal dependency. */
  public int purgeRetiredTargets(int limit) {
    requireOwnerTransaction();
    if (limit <= 0) throw new IllegalArgumentException("Bounded target seal purge required");
    var seals =
        dsl.fetch(
            "SELECT target_digest, target_binding, snapshot_uuid FROM entity_canonical_roster_retired_targets s "
                + "WHERE entity_roster_retired_target_purgeable(s.target_digest) ORDER BY target_digest, snapshot_uuid LIMIT ?",
            limit);
    int removed = 0;
    for (Record seal : seals) {
      byte[] target = seal.get("target_binding", byte[].class);
      // Target before row lock, matching attachment/acquisition. Never lock a seal row and
      // then wait for its target lock while an initializer may insert that same exact seal.
      boolean locked =
          Boolean.TRUE.equals(
              dsl.fetchSingle(
                      "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                      "entity-roster-target:" + targetDigest(target))
                  .get(0, Boolean.class));
      if (!locked) continue;
      removed +=
          dsl.execute(
              "DELETE FROM entity_canonical_roster_retired_targets WHERE target_digest = ? AND snapshot_uuid = ? "
                  + "AND target_binding = ? AND entity_roster_retired_target_purgeable(target_digest)",
              seal.get("target_digest", String.class),
              seal.get("snapshot_uuid", UUID.class),
              target);
    }
    return removed;
  }

  private boolean hasUnreleasedReference(UUID snapshot) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("entity_canonical_roster_retention_references r")
            .where(
                "r.snapshot_uuid = ? AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_releases x WHERE x.reference_uuid = r.reference_uuid)",
                snapshot));
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
    byte[] qualifiedTarget = null;
    if (kind != ReferenceKind.HOLD) {
      byte[] target = snapshotTargetBinding(binding);
      qualifiedTarget = target;
      lockTarget(target);
      if (hasUnknownTargetDependency()
          || hasRetiredTransfer(target)
          || dsl.fetchExists(
              dsl.selectOne()
                  .from("entity_canonical_roster_retired_targets")
                  .where("target_digest = ?", targetDigest(target)))) {
        throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
      }
    }
    lock(binding);
    if (kind != ReferenceKind.HOLD
        && !Arrays.equals(qualifiedTarget, snapshotTargetBinding(binding)))
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
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
            "SELECT COALESCE(max(extract(epoch FROM clock_timestamp() - retirement_observed_at)) "
                + "FILTER (WHERE lifecycle_state = 'RETIRED' "
                + "AND retirement_observed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()), "
                + "0)::bigint AS oldest_blocking, "
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
            + "(snapshot_uuid, snapshot_digest, entry_policy) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        binding.target().entryPolicy().name());
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
