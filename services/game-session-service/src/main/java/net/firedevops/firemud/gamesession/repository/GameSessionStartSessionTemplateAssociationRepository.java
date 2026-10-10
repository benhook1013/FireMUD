package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.InvalidProtocolBufferException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered immutable pin for the first normalized Game Design association selected by a
 * canonical StartSession owner attempt. It creates no instance, pointer, hold, admission, or grant.
 */
public final class GameSessionStartSessionTemplateAssociationRepository {
  // Local fail-closed storage budgets only; these do not imply a gRPC transport message limit.
  private static final int MAX_REQUEST_WIRE_BYTES =
      StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES + 1024;
  private static final int MAX_RESPONSE_WIRE_BYTES = 4 * 1024 * 1024;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String TABLE = "game_session_start_session_template_association_pin";

  private final DSLContext dsl;
  private final GameSessionStartSessionOperatorAttemptRepository attemptRepository;

  public GameSessionStartSessionTemplateAssociationRepository(
      DSLContext dsl, GameSessionStartSessionOperatorAttemptRepository attemptRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl is required");
    this.attemptRepository =
        Objects.requireNonNull(attemptRepository, "attemptRepository is required");
  }

  /**
   * Returns only the first retained selection after locking and revalidating the live owner claim.
   * A caller must use this before asking Game Design to resolve a selection again.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public Optional<PinnedAssociationSnapshot> findPinned(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim) {
    Objects.requireNonNull(claim, "claim is required");
    GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt =
        validateCurrentAttempt(claim);
    Record row = selectPin(claim.targetNamespace(), claim.controlPlaneRequestId(), true);
    return row == null
        ? Optional.empty()
        : Optional.of(
            decodeStored(row, claim.targetNamespace(), claim.controlPlaneRequestId(), attempt));
  }

  /**
   * Returns only an existing association pin through the restricted continuation capability. This
   * overload cannot create an initial selection or reconstruct an owner claim.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public Optional<PinnedAssociationSnapshot> findPinned(
      GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation continuation) {
    Objects.requireNonNull(continuation, "evidence continuation is required");
    GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt =
        attemptRepository.validateEvidenceContinuation(continuation);
    Record row =
        selectPin(continuation.targetNamespace(), continuation.controlPlaneRequestId(), true);
    return row == null
        ? Optional.empty()
        : Optional.of(
            decodeStored(
                row,
                continuation.targetNamespace(),
                continuation.controlPlaneRequestId(),
                attempt));
  }

  /**
   * Reads only the immutable first selection for historical owner reconciliation. It does not
   * require a live owner lease, select again, or grant mutation or admission authority.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<PinnedAssociationSnapshot> readHistoricalPinnedAssociation(
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID expectedOwnerAttemptId,
      long expectedOwnerFence) {
    requireOutsideOwnerTransaction();
    Objects.requireNonNull(tuple, "complete post-authorization tuple is required");
    String namespace = tuple.preAuthorizationTuple().action().scope().targetNamespace();
    String requestId = tuple.controlPlaneRequestId();
    Optional<GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot> historicalAttempt =
        attemptRepository.readHistoricalOwnerAttempt(
            tuple, expectedOwnerAttemptId, expectedOwnerFence);
    if (historicalAttempt.isEmpty()) {
      return Optional.empty();
    }

    Record row = selectPin(namespace, requestId, false);
    return row == null
        ? Optional.empty()
        : Optional.of(decodeStored(row, namespace, requestId, historicalAttempt.orElseThrow()));
  }

  /**
   * Pins the first configured selection or validates a later exact replay against the retained
   * selection. Only the byte-identical original response may replay INITIAL_CONFIGURED; a new
   * initial read cannot reselect after a pin exists.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public PinnedAssociationSnapshot pinInitialOrValidateExactReplay(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim, Result candidate) {
    Objects.requireNonNull(claim, "claim is required");
    Objects.requireNonNull(candidate, "candidate is required");
    byte[] requestWire =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(candidate.request()).toByteArray();
    byte[] responseWire =
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(candidate).toByteArray();
    requireWireBound(requestWire, responseWire);
    GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt =
        validateCurrentAttempt(claim);
    requireCandidateBoundToAttempt(claim, attempt, candidate);
    Record existing = selectPin(claim.targetNamespace(), claim.controlPlaneRequestId(), true);
    if (existing != null) {
      byte[] originalRequestWire = requiredBytes(existing, "association_request_wire");
      byte[] originalResponseWire = requiredBytes(existing, "association_response_wire");
      if (Arrays.equals(originalRequestWire, requestWire)
          && Arrays.equals(originalResponseWire, responseWire)) {
        return decodeStored(
            existing, claim.targetNamespace(), claim.controlPlaneRequestId(), attempt);
      }
      PinnedAssociationSnapshot original =
          decodeStored(existing, claim.targetNamespace(), claim.controlPlaneRequestId(), attempt);
      requireExactReplay(original, candidate);
      return original;
    }

    if (!(candidate.request().selection() instanceof InitialConfigured)) {
      throw new StartSessionTemplateAssociationConflictException(
          "An exact replay cannot create the original template association pin");
    }

    Association association = candidate.association();
    Record inserted = insertInitialPin(claim, attempt, association, requestWire, responseWire);
    if (inserted == null) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Game Session owner claim expired or changed before the initial association pin");
    }
    return decodeStored(inserted, claim.targetNamespace(), claim.controlPlaneRequestId(), attempt);
  }

  private Record insertInitialPin(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim,
      GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt,
      Association association,
      byte[] requestWire,
      byte[] responseWire) {
    byte[] exactTuple = attempt.postAuthorizationExecutionTuple();
    byte[] exactProjection = attempt.accountRedemptionProjection();
    if (exactProjection == null) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Template association pin requires the original attached Account projection");
    }
    return dsl.fetchOne(
        "INSERT INTO "
            + TABLE
            + " (target_namespace, control_plane_request_id, canonical_tenant_id, "
            + "owner_attempt_id, owner_fence, post_authorization_execution_tuple, "
            + "post_authorization_tuple_digest, account_redemption_projection_digest, "
            + "association_request_wire, association_response_wire, association_request_digest, "
            + "association_response_digest, template_id, canonical_version_id, selected_commit_id, "
            + "publish_workflow_id, publication_selection_digest, association_digest, "
            + "world_intake_request_id, world_operation_id, source_operation_id, "
            + "source_evidence_digest) "
            + "SELECT attempt.target_namespace, attempt.control_plane_request_id, "
            + "attempt.canonical_tenant_id, attempt.owner_attempt_id, attempt.owner_fence, "
            + "attempt.post_authorization_execution_tuple, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? "
            + "FROM game_session_start_session_operator_attempt attempt "
            + "WHERE attempt.target_namespace = ? AND attempt.control_plane_request_id = ? "
            + "AND attempt.canonical_tenant_id = ? "
            + "AND attempt.owner_attempt_id = ? AND attempt.owner_mutation_id = ? "
            + "AND attempt.claim_owner_id = ? AND attempt.owner_fence = ? "
            + "AND attempt.phase_state = 'OWNER_EXECUTION_PENDING' "
            + "AND attempt.account_redemption_projection IS NOT NULL "
            + "AND attempt.post_authorization_execution_tuple = ? "
            + "AND attempt.account_redemption_projection = ? "
            + "AND attempt.lease_expires_at > clock_timestamp() "
            + "RETURNING *",
        sha256(exactTuple),
        sha256(exactProjection),
        requestWire,
        responseWire,
        sha256(requestWire),
        sha256(responseWire),
        association.templateId(),
        association.canonicalVersionId(),
        association.selectedCommitId(),
        association.publishWorkflowId(),
        association.publicationSelectionDigest(),
        association.associationDigest(),
        association.intakeRequestId(),
        association.worldOperationId(),
        association.sourceOperationId(),
        association.sourceEvidenceDigest(),
        claim.targetNamespace(),
        claim.controlPlaneRequestId(),
        attempt.canonicalTenantId(),
        claim.ownerAttemptId(),
        claim.ownerMutationId(),
        claim.claimOwnerId(),
        claim.ownerFence(),
        exactTuple,
        exactProjection);
  }

  private GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot validateCurrentAttempt(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim) {
    GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt =
        attemptRepository.validateCurrentClaim(claim);
    if (!"OWNER_EXECUTION_PENDING".equals(attempt.phaseState())
        || attempt.accountRedemptionProjection() == null
        || !attempt.targetNamespace().equals(claim.targetNamespace())
        || !attempt.controlPlaneRequestId().equals(claim.controlPlaneRequestId())
        || !attempt.ownerAttemptId().equals(claim.ownerAttemptId())
        || attempt.ownerFence() != claim.ownerFence()) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Template association pin requires the exact pending, Account-attached owner attempt");
    }
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(
            attempt.postAuthorizationExecutionTuple());
    if (!Arrays.equals(tuple.canonicalBytes(), attempt.postAuthorizationExecutionTuple())
        || !tuple.controlPlaneRequestId().equals(attempt.controlPlaneRequestId())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(attempt.targetNamespace())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(attempt.canonicalTenantId())) {
      throw new IllegalStateException(
          "Persisted StartSession owner attempt differs from its canonical post-authorization tuple");
    }
    return attempt;
  }

  private static void requireCandidateBoundToAttempt(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim,
      GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt,
      Result candidate) {
    Request request = candidate.request();
    if (!request.targetNamespace().equals(attempt.targetNamespace())
        || !request.ownerAttemptId().equals(attempt.ownerAttemptId())
        || request.ownerFence() != attempt.ownerFence()
        || !Arrays.equals(
            request.canonicalPostAuthorizationTuple(), attempt.postAuthorizationExecutionTuple())
        || !request.targetNamespace().equals(claim.targetNamespace())
        || !request.decodedTuple().controlPlaneRequestId().equals(claim.controlPlaneRequestId())
        || !candidate.association().canonicalTenantId().equals(attempt.canonicalTenantId())) {
      throw new StartSessionTemplateAssociationConflictException(
          "Game Design association differs from the exact current StartSession owner attempt");
    }
  }

  private PinnedAssociationSnapshot decodeStored(
      Record row,
      String expectedNamespace,
      String expectedRequestId,
      GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attempt) {
    byte[] tupleBytes = requiredBytes(row, "post_authorization_execution_tuple");
    byte[] requestWire = requiredBytes(row, "association_request_wire");
    byte[] responseWire = requiredBytes(row, "association_response_wire");
    if (!requiredText(row, "target_namespace").equals(expectedNamespace)
        || !requiredText(row, "control_plane_request_id").equals(expectedRequestId)
        || !requiredUuid(row, "canonical_tenant_id").equals(attempt.canonicalTenantId())
        || !requiredUuid(row, "owner_attempt_id").equals(attempt.ownerAttemptId())
        || requiredLong(row, "owner_fence") != attempt.ownerFence()
        || !Arrays.equals(tupleBytes, attempt.postAuthorizationExecutionTuple())
        || !requiredText(row, "post_authorization_tuple_digest").equals(sha256(tupleBytes))
        || !requiredText(row, "account_redemption_projection_digest")
            .equals(sha256(attempt.accountRedemptionProjection()))
        || !requiredText(row, "association_request_digest").equals(sha256(requestWire))
        || !requiredText(row, "association_response_digest").equals(sha256(responseWire))) {
      throw new IllegalStateException(
          "Persisted StartSession template association pin differs from its owner attempt");
    }
    if (requestWire.length > MAX_REQUEST_WIRE_BYTES
        || responseWire.length > MAX_RESPONSE_WIRE_BYTES) {
      throw new IllegalStateException(
          "Persisted template association carrier exceeds its byte limit");
    }

    try {
      ReadStartSessionTemplateAssociationRequest requestMessage =
          ReadStartSessionTemplateAssociationRequest.parseFrom(requestWire);
      Request request = StartSessionTemplateAssociationReadGrpcCodec.fromRequest(requestMessage);
      if (!(request.selection() instanceof InitialConfigured)
          || !Arrays.equals(
              StartSessionTemplateAssociationReadGrpcCodec.toRequest(request).toByteArray(),
              requestWire)) {
        throw new IllegalStateException(
            "Persisted original association request is not a canonical initial selection");
      }
      ReadStartSessionTemplateAssociationResponse responseMessage =
          ReadStartSessionTemplateAssociationResponse.parseFrom(responseWire);
      Result result =
          StartSessionTemplateAssociationReadGrpcCodec.fromResponse(request, responseMessage);
      if (!Arrays.equals(
          StartSessionTemplateAssociationReadGrpcCodec.toResponse(result).toByteArray(),
          responseWire)) {
        throw new IllegalStateException(
            "Persisted association response is not canonically encoded");
      }
      requireNormalizedColumnsMatch(row, result);
      return new PinnedAssociationSnapshot(
          result,
          requiredText(row, "association_request_digest"),
          requiredText(row, "association_response_digest"),
          requiredInstant(row, "created_at"));
    } catch (InvalidProtocolBufferException | IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Persisted StartSession template association evidence is malformed", malformed);
    }
  }

  private static void requireNormalizedColumnsMatch(Record row, Result result) {
    Association association = result.association();
    if (!requiredText(row, "target_namespace").equals(association.targetNamespace())
        || !requiredUuid(row, "canonical_tenant_id").equals(association.canonicalTenantId())
        || requiredLong(row, "template_id") != association.templateId()
        || !requiredUuid(row, "canonical_version_id").equals(association.canonicalVersionId())
        || !requiredUuid(row, "selected_commit_id").equals(association.selectedCommitId())
        || !requiredText(row, "publish_workflow_id").equals(association.publishWorkflowId())
        || !requiredText(row, "publication_selection_digest")
            .equals(association.publicationSelectionDigest())
        || !requiredText(row, "association_digest").equals(association.associationDigest())
        || !requiredUuid(row, "world_intake_request_id").equals(association.intakeRequestId())
        || !requiredUuid(row, "world_operation_id").equals(association.worldOperationId())
        || !requiredUuid(row, "source_operation_id").equals(association.sourceOperationId())
        || !requiredText(row, "source_evidence_digest")
            .equals(association.sourceEvidenceDigest())) {
      throw new IllegalStateException(
          "Persisted template association columns differ from the retained typed response");
    }
  }

  private static void requireExactReplay(PinnedAssociationSnapshot original, Result candidate) {
    Request request = candidate.request();
    Request originalRequest = original.result().request();
    Association pinned = original.result().association();
    if (!(request.selection() instanceof ExactReplay replay)
        || !request.targetNamespace().equals(originalRequest.targetNamespace())
        || !Arrays.equals(
            request.canonicalPostAuthorizationTuple(),
            originalRequest.canonicalPostAuthorizationTuple())
        || !request.ownerAttemptId().equals(originalRequest.ownerAttemptId())
        || request.ownerFence() != originalRequest.ownerFence()
        || !replay.canonicalVersionId().equals(pinned.canonicalVersionId())
        || !replay.selectedCommitId().equals(pinned.selectedCommitId())
        || !replay.publishWorkflowId().equals(pinned.publishWorkflowId())
        || !replay.associationDigest().equals(pinned.associationDigest())
        || !sameAssociation(pinned, candidate.association())
        || !original.result().releaseBundle().equals(candidate.releaseBundle())
        || !original
            .result()
            .worldPublishedStartLocationEvidence()
            .equals(candidate.worldPublishedStartLocationEvidence())) {
      throw new StartSessionTemplateAssociationConflictException(
          "Exact replay differs from the original immutable template association selection");
    }
  }

  private static boolean sameAssociation(Association left, Association right) {
    var leftWorldRead = left.worldReadRequest();
    var rightWorldRead = right.worldReadRequest();
    return left.canonicalTenantId().equals(right.canonicalTenantId())
        && left.templateId() == right.templateId()
        && left.canonicalVersionId().equals(right.canonicalVersionId())
        && left.selectedCommitId().equals(right.selectedCommitId())
        && left.publishWorkflowId().equals(right.publishWorkflowId())
        && left.publicationSelectionDigest().equals(right.publicationSelectionDigest())
        && left.associationDigest().equals(right.associationDigest())
        && left.targetNamespace().equals(right.targetNamespace())
        && left.intakeRequestId().equals(right.intakeRequestId())
        && left.worldOperationId().equals(right.worldOperationId())
        && left.sourceOperationId().equals(right.sourceOperationId())
        && left.worldSlug().equals(right.worldSlug())
        && left.sourceEvidenceDigest().equals(right.sourceEvidenceDigest())
        && leftWorldRead.schemaVersion() == rightWorldRead.schemaVersion()
        && leftWorldRead.targetNamespace().equals(rightWorldRead.targetNamespace())
        && leftWorldRead.intakeRequestId().equals(rightWorldRead.intakeRequestId())
        && leftWorldRead.canonicalTenantId().equals(rightWorldRead.canonicalTenantId())
        && left.worldReceipt().equals(right.worldReceipt());
  }

  private Record selectPin(String namespace, String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + TABLE
            + " WHERE target_namespace = ? AND control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        namespace,
        requestId);
  }

  private static void requireOutsideOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Historical StartSession association evidence requires a read outside the owner "
              + "transaction");
    }
  }

  private static void requireWireBound(byte[] requestWire, byte[] responseWire) {
    if (requestWire.length == 0
        || responseWire.length == 0
        || requestWire.length > MAX_REQUEST_WIRE_BYTES
        || responseWire.length > MAX_RESPONSE_WIRE_BYTES) {
      throw new StartSessionTemplateAssociationConflictException(
          "Template association carrier exceeds the local fail-closed storage budget");
    }
  }

  private static String sha256(byte[] value) {
    Objects.requireNonNull(value, "digest input is required");
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String requiredText(Record row, String field) {
    String value = row == null ? null : row.get(field, String.class);
    if (value == null)
      throw new IllegalStateException("Persisted association pin is missing " + field);
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row == null ? null : row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalStateException("Persisted association pin is missing " + field);
    }
    return value;
  }

  private static long requiredLong(Record row, String field) {
    Long value = row == null ? null : row.get(field, Long.class);
    if (value == null)
      throw new IllegalStateException("Persisted association pin is missing " + field);
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row == null ? null : row.get(field, byte[].class);
    if (value == null)
      throw new IllegalStateException("Persisted association pin is missing " + field);
    return value.clone();
  }

  private static Instant requiredInstant(Record row, String field) {
    java.time.OffsetDateTime value =
        row == null ? null : row.get(field, java.time.OffsetDateTime.class);
    if (value == null)
      throw new IllegalStateException("Persisted association pin is missing " + field);
    return value.toInstant();
  }

  /** Original immutable typed selection and exact-wire digests; no owner credential is exposed. */
  public record PinnedAssociationSnapshot(
      Result result, String requestDigest, String responseDigest, Instant createdAt) {
    public PinnedAssociationSnapshot {
      Objects.requireNonNull(result, "result is required");
      Objects.requireNonNull(requestDigest, "requestDigest is required");
      Objects.requireNonNull(responseDigest, "responseDigest is required");
      Objects.requireNonNull(createdAt, "createdAt is required");
    }
  }

  public static final class StartSessionTemplateAssociationConflictException
      extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public StartSessionTemplateAssociationConflictException(String message) {
      super(message);
    }
  }
}
