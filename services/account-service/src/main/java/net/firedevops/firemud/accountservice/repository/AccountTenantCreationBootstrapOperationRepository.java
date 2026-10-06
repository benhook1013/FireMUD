package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable Account owner request, source snapshot, and result receipt for creator bootstrap. */
@Repository
public class AccountTenantCreationBootstrapOperationRepository {
  private static final String TABLE = "account_tenant_creation_bootstrap_operations";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring transaction proxy requires a non-final class; constructor only validates its internal collaborator and publishes no partial instance.")
  public AccountTenantCreationBootstrapOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /** Claims one source and baseline, or returns its exact immutable original result. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Claim claim(
      FreshTenantCreatorEvidence evidence,
      byte[] creatorEvidencePayload,
      byte[] sourceSnapshotPayload,
      String sourceSnapshotDigest,
      byte[] requestPayload,
      String requestDigest,
      long baselineMembershipVersion,
      long baselineMembershipAuthorityGeneration) {
    requireWriteTransaction();
    Objects.requireNonNull(evidence, "creator evidence is required");
    requireBytes(creatorEvidencePayload, "creator evidence payload");
    requireBytes(sourceSnapshotPayload, "Account source snapshot payload");
    requireBytes(requestPayload, "creator-bootstrap request payload");
    requireDigest(sourceSnapshotDigest, "source snapshot digest");
    requireDigest(requestDigest, "request digest");
    if (baselineMembershipVersion <= 0L || baselineMembershipAuthorityGeneration <= 0L) {
      throw new IllegalArgumentException("Creator-bootstrap baseline counters must be positive");
    }
    var creation = evidence.creationEvidence();
    UUID requestId = evidence.accountAuthorizationOperationId();
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (request_id, schema_version, initiating_account_uuid, tenant_uuid, "
                + "creation_request_id, creation_operation_id, account_authorization_operation_id, "
                + "account_authorization_digest, creator_evidence_digest, creator_evidence_payload, "
                + "source_snapshot_payload, source_snapshot_digest, request_payload, request_digest, "
                + "baseline_membership_version, baseline_membership_authority_generation, "
                + "baseline_event_sequence, status) "
                + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'IN_PROGRESS') "
                + "ON CONFLICT DO NOTHING",
            requestId,
            evidence.initiatingAccountId(),
            creation.canonicalTenantId(),
            creation.creationRequestId(),
            creation.operationId(),
            evidence.accountAuthorizationOperationId(),
            evidence.accountAuthorizationDigest(),
            evidence.evidenceDigest(),
            creatorEvidencePayload,
            sourceSnapshotPayload,
            sourceSnapshotDigest,
            requestPayload,
            requestDigest,
            baselineMembershipVersion,
            baselineMembershipAuthorityGeneration);
    if (inserted == 1) {
      StoredOperation stored =
          findForUpdate(requestId)
              .orElseThrow(
                  () -> new IllegalStateException("Claimed creator-bootstrap operation is absent"));
      requireSameInput(
          stored,
          evidence,
          creatorEvidencePayload,
          sourceSnapshotPayload,
          sourceSnapshotDigest,
          requestPayload,
          requestDigest,
          baselineMembershipVersion,
          baselineMembershipAuthorityGeneration);
      if (!"IN_PROGRESS".equals(stored.status())) {
        throw new IllegalStateException("New creator-bootstrap claim is not in progress");
      }
      return new Claim(true, stored);
    }
    if (inserted != 0) {
      throw new IllegalStateException("Creator-bootstrap operation claim was ambiguous");
    }

    Optional<StoredOperation> sameRequest = findForUpdate(requestId);
    if (sameRequest.isPresent()) {
      StoredOperation stored = sameRequest.orElseThrow();
      requireSameInput(
          stored,
          evidence,
          creatorEvidencePayload,
          sourceSnapshotPayload,
          sourceSnapshotDigest,
          requestPayload,
          requestDigest,
          baselineMembershipVersion,
          baselineMembershipAuthorityGeneration);
      if (!"COMMITTED".equals(stored.status())) {
        throw new IllegalStateException(
            "Incomplete creator-bootstrap operation cannot be replayed");
      }
      return new Claim(false, stored);
    }

    Optional<UUID> tenantOperation = findRequestIdByTenantForUpdate(creation.canonicalTenantId());
    if (tenantOperation.isPresent()) {
      throw new OperationConflictException(
          "Fresh tenant already has a different immutable creator-bootstrap operation");
    }
    throw new IllegalStateException("Conflicting creator-bootstrap operation claim disappeared");
  }

  /** Returns one request under its row lock for exact retry verification. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredOperation> findForUpdate(UUID requestId) {
    requireWriteTransaction();
    Objects.requireNonNull(requestId, "creator-bootstrap request ID is required");
    Record row =
        dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", requestId);
    return row == null ? Optional.empty() : Optional.of(toStoredOperation(row));
  }

  /** Locks the tenant's unique original creator operation when a request ID differs. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> findRequestIdByTenantForUpdate(UUID tenantUuid) {
    requireWriteTransaction();
    Objects.requireNonNull(tenantUuid, "tenant UUID is required");
    Record row =
        dsl.fetchOne(
            "SELECT request_id FROM " + TABLE + " WHERE tenant_uuid = ? FOR UPDATE", tenantUuid);
    return row == null ? Optional.empty() : Optional.of(row.get("request_id", UUID.class));
  }

  /**
   * Requires no older Account membership receipt or operation history for this exact UUID pair. The
   * caller already holds the initiator Account row fence and the Account authority snapshot.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void assertNoContradictoryMembershipHistory(UUID accountUuid, UUID tenantUuid) {
    requireWriteTransaction();
    boolean receiptHead =
        exists(
            "SELECT EXISTS (SELECT 1 FROM account_membership_transition_receipt_stream_heads "
                + "WHERE account_uuid = ? AND tenant_uuid = ?)",
            accountUuid,
            tenantUuid);
    boolean priorJoinOperation =
        exists(
            "SELECT EXISTS (SELECT 1 FROM account_join_operations "
                + "WHERE account_uuid = ? AND tenant_uuid = ?)",
            accountUuid,
            tenantUuid);
    boolean priorRoleOperation =
        exists(
            "SELECT EXISTS (SELECT 1 FROM account_tenant_role_operation_members "
                + "WHERE account_uuid = ? AND tenant_uuid = ?)",
            accountUuid,
            tenantUuid);
    if (receiptHead || priorJoinOperation || priorRoleOperation) {
      throw new IllegalStateException(
          "Fresh creator membership pair has contradictory retained Account history");
    }
  }

  /** Completes the exact claim with the membership event, audit, and immutable result receipt. */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredOperation complete(UUID requestId, Completion completion) {
    requireWriteTransaction();
    Objects.requireNonNull(requestId, "creator-bootstrap request ID is required");
    Objects.requireNonNull(completion, "creator-bootstrap completion is required");
    int updated =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET membership_id = ?, membership_version = ?, "
                + "membership_authority_generation = ?, membership_lifecycle_state = ?, "
                + "gameplay_admission_allowed = ?, membership_roles_payload = ?, "
                + "event_stream_key = ?, event_request_id = ?, event_sequence = ?, event_id = ?, "
                + "event_digest = ?, caller_bound_authority_invalidated = ?, event_payload = ?, "
                + "audit_event_id = ?, audit_event_type = ?, audit_occurred_at = CAST(? AS TIMESTAMPTZ), "
                + "audit_payload_digest = ?, audit_payload = ?, result_payload = ?, result_digest = ?, "
                + "status = 'COMMITTED' WHERE request_id = ? AND status = 'IN_PROGRESS'",
            completion.membershipId(),
            completion.membershipVersion(),
            completion.membershipAuthorityGeneration(),
            completion.membershipLifecycleState(),
            completion.gameplayAdmissionAllowed(),
            completion.membershipRolesPayload(),
            completion.eventStreamKey(),
            completion.eventRequestId(),
            completion.eventSequence(),
            completion.eventId(),
            completion.eventDigest(),
            completion.callerBoundAuthorityInvalidated(),
            completion.eventPayload(),
            completion.auditEventId(),
            completion.auditEventType(),
            OffsetDateTime.ofInstant(completion.auditOccurredAt(), ZoneOffset.UTC).toString(),
            completion.auditPayloadDigest(),
            completion.auditPayload(),
            completion.resultPayload(),
            completion.resultDigest(),
            requestId);
    if (updated != 1) {
      throw new IllegalStateException("Creator-bootstrap operation was not completed exactly once");
    }
    StoredOperation stored =
        findForUpdate(requestId)
            .orElseThrow(
                () -> new IllegalStateException("Completed creator-bootstrap receipt is absent"));
    if (!"COMMITTED".equals(stored.status())
        || stored.membershipId() != completion.membershipId()
        || stored.membershipVersion() != completion.membershipVersion()
        || stored.membershipAuthorityGeneration() != completion.membershipAuthorityGeneration()
        || !completion.membershipLifecycleState().equals(stored.membershipLifecycleState())
        || !Boolean.valueOf(completion.gameplayAdmissionAllowed())
            .equals(stored.gameplayAdmissionAllowed())
        || !Arrays.equals(stored.membershipRolesPayload(), completion.membershipRolesPayload())
        || !stored.eventStreamKey().equals(completion.eventStreamKey())
        || !stored.eventRequestId().equals(completion.eventRequestId())
        || stored.eventSequence() != completion.eventSequence()
        || !stored.eventId().equals(completion.eventId())
        || !stored.eventDigest().equals(completion.eventDigest())
        || !Arrays.equals(stored.eventPayload(), completion.eventPayload())
        || !stored.auditEventId().equals(completion.auditEventId())
        || !stored.auditEventType().equals(completion.auditEventType())
        || !stored.auditOccurredAt().equals(completion.auditOccurredAt())
        || !stored.auditPayloadDigest().equals(completion.auditPayloadDigest())
        || !Arrays.equals(stored.auditPayload(), completion.auditPayload())
        || !Arrays.equals(stored.resultPayload(), completion.resultPayload())
        || !stored.resultDigest().equals(completion.resultDigest())) {
      throw new IllegalStateException("Creator-bootstrap committed receipt readback differs");
    }
    return stored;
  }

  private boolean exists(String query, Object... bindings) {
    Record result = dsl.fetchOne(query, bindings);
    return result != null && Boolean.TRUE.equals(result.get(0, Boolean.class));
  }

  private static void requireSameInput(
      StoredOperation stored,
      FreshTenantCreatorEvidence evidence,
      byte[] creatorEvidencePayload,
      byte[] sourceSnapshotPayload,
      String sourceSnapshotDigest,
      byte[] requestPayload,
      String requestDigest,
      long baselineMembershipVersion,
      long baselineMembershipAuthorityGeneration) {
    var creation = evidence.creationEvidence();
    if (!stored.requestId().equals(evidence.accountAuthorizationOperationId())
        || !stored.initiatingAccountUuid().equals(evidence.initiatingAccountId())
        || !stored.tenantUuid().equals(creation.canonicalTenantId())
        || !stored.creationRequestId().equals(creation.creationRequestId())
        || !stored.creationOperationId().equals(creation.operationId())
        || !stored
            .accountAuthorizationOperationId()
            .equals(evidence.accountAuthorizationOperationId())
        || !stored.accountAuthorizationDigest().equals(evidence.accountAuthorizationDigest())
        || !stored.creatorEvidenceDigest().equals(evidence.evidenceDigest())
        || !Arrays.equals(stored.creatorEvidencePayload(), creatorEvidencePayload)
        || !Arrays.equals(stored.sourceSnapshotPayload(), sourceSnapshotPayload)
        || !stored.sourceSnapshotDigest().equals(sourceSnapshotDigest)
        || !Arrays.equals(stored.requestPayload(), requestPayload)
        || !stored.requestDigest().equals(requestDigest)
        || stored.baselineMembershipVersion() != baselineMembershipVersion
        || stored.baselineMembershipAuthorityGeneration() != baselineMembershipAuthorityGeneration
        || stored.baselineEventSequence() != 0L) {
      throw new OperationConflictException(
          "Creator-bootstrap operation ID was reused with changed source or Account snapshot");
    }
  }

  private static StoredOperation toStoredOperation(Record row) {
    return new StoredOperation(
        required(row.get("request_id", UUID.class), "request ID"),
        required(row.get("initiating_account_uuid", UUID.class), "initiating Account UUID"),
        required(row.get("tenant_uuid", UUID.class), "tenant UUID"),
        required(row.get("creation_request_id", UUID.class), "creation request ID"),
        required(row.get("creation_operation_id", UUID.class), "creation operation ID"),
        required(
            row.get("account_authorization_operation_id", UUID.class),
            "Account authorization operation ID"),
        required(
            row.get("account_authorization_digest", String.class), "Account authorization digest"),
        required(row.get("creator_evidence_digest", String.class), "creator evidence digest"),
        required(row.get("creator_evidence_payload", byte[].class), "creator evidence payload"),
        required(row.get("source_snapshot_payload", byte[].class), "source snapshot payload"),
        required(row.get("source_snapshot_digest", String.class), "source snapshot digest"),
        required(row.get("request_payload", byte[].class), "request payload"),
        required(row.get("request_digest", String.class), "request digest"),
        required(row.get("baseline_membership_version", Long.class), "baseline membership version"),
        required(
            row.get("baseline_membership_authority_generation", Long.class),
            "baseline membership authority generation"),
        required(row.get("baseline_event_sequence", Long.class), "baseline event sequence"),
        required(row.get("status", String.class), "operation status"),
        row.get("membership_id", Long.class),
        row.get("membership_version", Long.class),
        row.get("membership_authority_generation", Long.class),
        row.get("membership_lifecycle_state", String.class),
        row.get("gameplay_admission_allowed", Boolean.class),
        row.get("membership_roles_payload", byte[].class),
        row.get("event_stream_key", String.class),
        row.get("event_request_id", String.class),
        row.get("event_sequence", Long.class),
        row.get("event_id", String.class),
        row.get("event_digest", String.class),
        row.get("caller_bound_authority_invalidated", Boolean.class),
        row.get("event_payload", byte[].class),
        row.get("audit_event_id", UUID.class),
        row.get("audit_event_type", String.class),
        toInstant(row.get("audit_occurred_at", OffsetDateTime.class)),
        row.get("audit_payload_digest", String.class),
        row.get("audit_payload", byte[].class),
        row.get("result_payload", byte[].class),
        row.get("result_digest", String.class),
        toInstant(row.get("committed_at", OffsetDateTime.class)));
  }

  private static <T> T required(T value, String label) {
    if (value == null) {
      throw new IllegalStateException("Creator-bootstrap stored " + label + " is absent");
    }
    return value;
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static void requireBytes(byte[] value, String label) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Creator-bootstrap " + label + " is required");
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Creator-bootstrap " + label + " is malformed");
    }
  }

  private static void requireWriteTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Creator-bootstrap owner operation requires a write transaction");
    }
  }

  public record Claim(boolean claimed, StoredOperation operation) {
    public Claim {
      Objects.requireNonNull(operation, "claimed creator-bootstrap operation is required");
    }
  }

  public record Completion(
      long membershipId,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      byte[] membershipRolesPayload,
      String eventStreamKey,
      String eventRequestId,
      long eventSequence,
      String eventId,
      String eventDigest,
      boolean callerBoundAuthorityInvalidated,
      byte[] eventPayload,
      UUID auditEventId,
      String auditEventType,
      Instant auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      byte[] resultPayload,
      String resultDigest) {
    public Completion {
      if (membershipId <= 0L
          || membershipVersion <= 0L
          || membershipAuthorityGeneration <= 0L
          || membershipLifecycleState == null
          || membershipRolesPayload == null
          || eventStreamKey == null
          || eventRequestId == null
          || eventSequence <= 0L
          || eventId == null
          || eventDigest == null
          || eventPayload == null
          || auditEventId == null
          || auditEventType == null
          || auditOccurredAt == null
          || auditPayloadDigest == null
          || auditPayload == null
          || resultPayload == null
          || resultDigest == null) {
        throw new IllegalArgumentException("Creator-bootstrap completion evidence is incomplete");
      }
      membershipRolesPayload = membershipRolesPayload.clone();
      eventPayload = eventPayload.clone();
      auditPayload = auditPayload.clone();
      resultPayload = resultPayload.clone();
    }

    @Override
    public byte[] membershipRolesPayload() {
      return membershipRolesPayload.clone();
    }

    @Override
    public byte[] eventPayload() {
      return eventPayload.clone();
    }

    @Override
    public byte[] auditPayload() {
      return auditPayload.clone();
    }

    @Override
    public byte[] resultPayload() {
      return resultPayload.clone();
    }
  }

  public record StoredOperation(
      UUID requestId,
      UUID initiatingAccountUuid,
      UUID tenantUuid,
      UUID creationRequestId,
      UUID creationOperationId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest,
      String creatorEvidenceDigest,
      byte[] creatorEvidencePayload,
      byte[] sourceSnapshotPayload,
      String sourceSnapshotDigest,
      byte[] requestPayload,
      String requestDigest,
      long baselineMembershipVersion,
      long baselineMembershipAuthorityGeneration,
      long baselineEventSequence,
      String status,
      Long membershipId,
      Long membershipVersion,
      Long membershipAuthorityGeneration,
      String membershipLifecycleState,
      Boolean gameplayAdmissionAllowed,
      byte[] membershipRolesPayload,
      String eventStreamKey,
      String eventRequestId,
      Long eventSequence,
      String eventId,
      String eventDigest,
      Boolean callerBoundAuthorityInvalidated,
      byte[] eventPayload,
      UUID auditEventId,
      String auditEventType,
      Instant auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      byte[] resultPayload,
      String resultDigest,
      Instant committedAt) {
    public StoredOperation {
      creatorEvidencePayload = cloneBytes(creatorEvidencePayload);
      sourceSnapshotPayload = cloneBytes(sourceSnapshotPayload);
      requestPayload = cloneBytes(requestPayload);
      membershipRolesPayload = cloneBytes(membershipRolesPayload);
      eventPayload = cloneBytes(eventPayload);
      auditPayload = cloneBytes(auditPayload);
      resultPayload = cloneBytes(resultPayload);
    }

    @Override
    public byte[] creatorEvidencePayload() {
      return cloneBytes(creatorEvidencePayload);
    }

    @Override
    public byte[] sourceSnapshotPayload() {
      return cloneBytes(sourceSnapshotPayload);
    }

    @Override
    public byte[] requestPayload() {
      return cloneBytes(requestPayload);
    }

    @Override
    public byte[] membershipRolesPayload() {
      return cloneBytes(membershipRolesPayload);
    }

    @Override
    public byte[] eventPayload() {
      return cloneBytes(eventPayload);
    }

    @Override
    public byte[] auditPayload() {
      return cloneBytes(auditPayload);
    }

    @Override
    public byte[] resultPayload() {
      return cloneBytes(resultPayload);
    }

    public AccountTenantCreationBootstrapResult result() {
      if (!"COMMITTED".equals(status)
          || membershipVersion == null
          || membershipAuthorityGeneration == null
          || eventSequence == null
          || eventStreamKey == null
          || eventRequestId == null
          || eventId == null
          || eventDigest == null
          || auditEventId == null
          || auditEventType == null
          || auditPayloadDigest == null
          || resultDigest == null) {
        throw new IllegalStateException("Incomplete creator-bootstrap result is not replayable");
      }
      return new AccountTenantCreationBootstrapResult(
          requestId,
          requestDigest,
          initiatingAccountUuid,
          tenantUuid,
          creatorEvidenceDigest,
          sourceSnapshotDigest,
          java.util.Map.of(tenantUuid.toString(), Long.toString(membershipVersion)),
          Long.toString(membershipAuthorityGeneration),
          List.of("tenantAdmin"),
          false,
          eventStreamKey,
          eventRequestId,
          eventSequence,
          eventId,
          eventDigest,
          auditEventId,
          auditEventType,
          auditPayloadDigest,
          resultDigest);
    }

    private static byte[] cloneBytes(byte[] bytes) {
      return bytes == null ? null : bytes.clone();
    }
  }

  public static final class OperationConflictException extends IllegalStateException {
    public OperationConflictException(String message) {
      super(message);
    }
  }
}
