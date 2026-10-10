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
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.Resolved;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unregistered immutable descriptor outcome pin tied to the original StartSession association. It
 * creates no instance, pointer, hold, admission, or activation authority.
 */
public final class GameSessionStartSessionLaunchDescriptorRepository {
  // Local fail-closed storage budgets; these do not imply a gRPC transport message limit.
  private static final int MAX_REQUEST_WIRE_BYTES =
      StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES + 1024;
  private static final int MAX_RESPONSE_WIRE_BYTES = 8 * 1024 * 1024;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String TABLE = "game_session_start_session_launch_descriptor_pin";

  private final DSLContext dsl;
  private final GameSessionStartSessionTemplateAssociationRepository associationRepository;

  public GameSessionStartSessionLaunchDescriptorRepository(
      DSLContext dsl, GameSessionStartSessionTemplateAssociationRepository associationRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl is required");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository is required");
  }

  /** Reads only a descriptor outcome pinned to the exact currently valid original owner claim. */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public Optional<PinnedLaunchDescriptorSnapshot> findPinned(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim) {
    Objects.requireNonNull(claim, "claim is required");
    Optional<GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot>
        association = associationRepository.findPinned(claim);
    Record row = selectPin(claim.targetNamespace(), claim.controlPlaneRequestId(), true);
    if (row == null) {
      return Optional.empty();
    }
    if (association.isEmpty()) {
      throw new IllegalStateException(
          "Persisted StartSession descriptor pin has no original association pin");
    }
    return Optional.of(
        decodeStored(
            row,
            claim.targetNamespace(),
            claim.controlPlaneRequestId(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            association.orElseThrow()));
  }

  /** Reads a retained descriptor only after the opaque continuation revalidates its owner row. */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public Optional<PinnedLaunchDescriptorSnapshot> findPinned(
      GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation continuation) {
    Objects.requireNonNull(continuation, "evidence continuation is required");
    Optional<GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot>
        association = associationRepository.findPinned(continuation);
    Record row = selectPin(continuation, true);
    if (row == null) {
      return Optional.empty();
    }
    if (association.isEmpty()) {
      throw new IllegalStateException(
          "Persisted StartSession descriptor pin has no original association pin");
    }
    return Optional.of(
        decodeStored(
            row,
            continuation.targetNamespace(),
            continuation.controlPlaneRequestId(),
            continuation.ownerAttemptId(),
            continuation.ownerFence(),
            association.orElseThrow()));
  }

  /**
   * Pins one exact-replay descriptor outcome under the current original claim. The exact replay
   * request is derived only from the already retained association; this method never selects.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public PinnedLaunchDescriptorSnapshot pin(
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim, Resolved candidate) {
    Objects.requireNonNull(claim, "claim is required");
    Objects.requireNonNull(candidate, "candidate is required");
    GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot association =
        associationRepository
            .findPinned(claim)
            .orElseThrow(
                () ->
                    new StartSessionLaunchDescriptorConflictException(
                        "An original template association pin is required before descriptor pinning"));
    PreparedCandidate prepared = prepareCandidate(association, candidate);
    Record existing = selectPin(claim.targetNamespace(), claim.controlPlaneRequestId(), true);
    if (existing != null) {
      PinnedLaunchDescriptorSnapshot retained =
          decodeStored(
              existing,
              claim.targetNamespace(),
              claim.controlPlaneRequestId(),
              claim.ownerAttemptId(),
              claim.ownerFence(),
              association);
      // The owner phase epoch is a fresh same-owner snapshot value, not part of the immutable
      // selection. prepareCandidate already rejects every changed association/release/World field.
      if (Arrays.equals(retained.requestWire(), prepared.requestWire())
          && (Arrays.equals(retained.responseWire(), prepared.responseWire())
              || candidate.outcome().equals(retained.resolved().outcome()))) {
        return retained;
      }
      throw new StartSessionLaunchDescriptorConflictException(
          "Descriptor outcome differs from the original immutable StartSession pin");
    }

    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + TABLE
                + " (target_namespace, control_plane_request_id, canonical_tenant_id, "
                + "owner_attempt_id, owner_fence, association_request_digest, "
                + "association_response_digest, descriptor_request_wire, descriptor_response_wire, "
                + "descriptor_request_digest, descriptor_response_digest) "
                + "SELECT attempt.target_namespace, attempt.control_plane_request_id, "
                + "attempt.canonical_tenant_id, attempt.owner_attempt_id, attempt.owner_fence, "
                + "association.association_request_digest, association.association_response_digest, "
                + "?, ?, ?, ? "
                + "FROM game_session_start_session_operator_attempt attempt "
                + "JOIN game_session_start_session_template_association_pin association "
                + "ON association.target_namespace = attempt.target_namespace "
                + "AND association.control_plane_request_id = attempt.control_plane_request_id "
                + "AND association.canonical_tenant_id = attempt.canonical_tenant_id "
                + "AND association.owner_attempt_id = attempt.owner_attempt_id "
                + "AND association.owner_fence = attempt.owner_fence "
                + "AND association.post_authorization_execution_tuple "
                + "= attempt.post_authorization_execution_tuple "
                + "WHERE attempt.target_namespace = ? "
                + "AND attempt.control_plane_request_id = ? "
                + "AND attempt.owner_attempt_id = ? AND attempt.owner_mutation_id = ? "
                + "AND attempt.claim_owner_id = ? AND attempt.owner_fence = ? "
                + "AND attempt.phase_state = 'OWNER_EXECUTION_PENDING' "
                + "AND attempt.account_redemption_projection IS NOT NULL "
                + "AND attempt.lease_expires_at > clock_timestamp() "
                + "RETURNING *",
            prepared.requestWire(),
            prepared.responseWire(),
            sha256(prepared.requestWire()),
            sha256(prepared.responseWire()),
            claim.targetNamespace(),
            claim.controlPlaneRequestId(),
            claim.ownerAttemptId(),
            claim.ownerMutationId(),
            claim.claimOwnerId(),
            claim.ownerFence());
    if (inserted == null) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Game Session owner claim is stale or its attached association changed "
              + "before descriptor pinning");
    }
    return decodeStored(
        inserted,
        claim.targetNamespace(),
        claim.controlPlaneRequestId(),
        claim.ownerAttemptId(),
        claim.ownerFence(),
        association);
  }

  /**
   * Pins an exact descriptor through the restricted continuation. The SQL statement repeats the
   * stored owner identity, canonical tuple, exact attached projection, and database-clock lease
   * predicate at the write boundary.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public PinnedLaunchDescriptorSnapshot pin(
      GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation continuation,
      Resolved candidate) {
    Objects.requireNonNull(continuation, "evidence continuation is required");
    Objects.requireNonNull(candidate, "candidate is required");
    GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot association =
        associationRepository
            .findPinned(continuation)
            .orElseThrow(
                () ->
                    new StartSessionLaunchDescriptorConflictException(
                        "An existing template association pin is required before descriptor continuation"));
    PreparedCandidate prepared = prepareCandidate(association, candidate);
    Record existing = selectPin(continuation, true);
    if (existing != null) {
      PinnedLaunchDescriptorSnapshot retained =
          decodeStored(
              existing,
              continuation.targetNamespace(),
              continuation.controlPlaneRequestId(),
              continuation.ownerAttemptId(),
              continuation.ownerFence(),
              association);
      if (Arrays.equals(retained.requestWire(), prepared.requestWire())
          && (Arrays.equals(retained.responseWire(), prepared.responseWire())
              || candidate.outcome().equals(retained.resolved().outcome()))) {
        return retained;
      }
      throw new StartSessionLaunchDescriptorConflictException(
          "Descriptor outcome differs from the original immutable StartSession pin");
    }

    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + TABLE
                + " (target_namespace, control_plane_request_id, canonical_tenant_id, "
                + "owner_attempt_id, owner_fence, association_request_digest, "
                + "association_response_digest, descriptor_request_wire, descriptor_response_wire, "
                + "descriptor_request_digest, descriptor_response_digest) "
                + "SELECT attempt.target_namespace, attempt.control_plane_request_id, "
                + "attempt.canonical_tenant_id, attempt.owner_attempt_id, attempt.owner_fence, "
                + "association.association_request_digest, association.association_response_digest, "
                + "?, ?, ?, ? "
                + "FROM game_session_start_session_operator_attempt attempt "
                + "JOIN game_session_start_session_template_association_pin association "
                + "ON association.target_namespace = attempt.target_namespace "
                + "AND association.control_plane_request_id = attempt.control_plane_request_id "
                + "AND association.canonical_tenant_id = attempt.canonical_tenant_id "
                + "AND association.owner_attempt_id = attempt.owner_attempt_id "
                + "AND association.owner_fence = attempt.owner_fence "
                + "AND association.post_authorization_execution_tuple "
                + "= attempt.post_authorization_execution_tuple "
                + "AND association.association_request_digest = ? "
                + "AND association.association_response_digest = ? "
                + "WHERE attempt.target_namespace = ? "
                + "AND attempt.control_plane_request_id = ? "
                + "AND attempt.owner_attempt_id = ? AND attempt.owner_mutation_id = ? "
                + "AND attempt.claim_owner_id = ? AND attempt.owner_fence = ? "
                + "AND attempt.phase_state = 'OWNER_EXECUTION_PENDING' "
                + "AND attempt.post_authorization_execution_tuple = ? "
                + "AND attempt.account_redemption_projection = ? "
                + "AND ?::timestamptz > clock_timestamp() "
                + "AND attempt.lease_expires_at > clock_timestamp() "
                + "RETURNING *",
            prepared.requestWire(),
            prepared.responseWire(),
            sha256(prepared.requestWire()),
            sha256(prepared.responseWire()),
            association.requestDigest(),
            association.responseDigest(),
            continuation.targetNamespace(),
            continuation.controlPlaneRequestId(),
            continuation.ownerAttemptId(),
            continuation.ownerMutationId(),
            continuation.claimOwnerId(),
            continuation.ownerFence(),
            continuation.postAuthorizationExecutionTuple(),
            continuation.accountRedemptionProjection(),
            originalAuthorizationExpiresAt(continuation));
    if (inserted == null) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Evidence continuation expired or changed before the descriptor pin statement");
    }
    return decodeStored(
        inserted,
        continuation.targetNamespace(),
        continuation.controlPlaneRequestId(),
        continuation.ownerAttemptId(),
        continuation.ownerFence(),
        association);
  }

  private static PreparedCandidate prepareCandidate(
      GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot pinned,
      Resolved candidate) {
    Result original = pinned.result();
    Association association = original.association();
    Request expectedRequest = exactReplayRequest(original);
    Result expectedAssociation =
        new Result(
            expectedRequest,
            association,
            original.releaseBundle(),
            original.worldPublishedStartLocationEvidence(),
            candidate.associationRead().phaseEpoch());
    byte[] expectedAssociationWire =
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(expectedAssociation).toByteArray();
    byte[] candidateAssociationWire =
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(candidate.associationRead())
            .toByteArray();
    if (!expectedRequest.equals(candidate.associationRead().request())
        || !Arrays.equals(expectedAssociationWire, candidateAssociationWire)) {
      throw new StartSessionLaunchDescriptorConflictException(
          "Descriptor response association differs from the retained exact "
              + "StartSession selection");
    }

    ReadStartSessionTemplateAssociationRequest requestMessage =
        StartSessionLaunchDescriptorGrpcCodec.toRequest(expectedRequest);
    ResolveStartSessionLaunchDescriptorResponse responseMessage =
        StartSessionLaunchDescriptorGrpcCodec.toResponse(
            expectedRequest, candidate.associationRead(), candidate.outcome());
    Resolved decoded =
        StartSessionLaunchDescriptorGrpcCodec.fromResponse(expectedRequest, responseMessage);
    if (!decoded.equals(candidate)
        || !Arrays.equals(
            StartSessionLaunchDescriptorGrpcCodec.toResponse(
                    expectedRequest, decoded.associationRead(), decoded.outcome())
                .toByteArray(),
            responseMessage.toByteArray())) {
      throw new StartSessionLaunchDescriptorConflictException(
          "Descriptor outcome is not the canonical closed StartSession response");
    }
    byte[] requestWire = requestMessage.toByteArray();
    byte[] responseWire = responseMessage.toByteArray();
    requireWireBound(requestWire, responseWire);
    return new PreparedCandidate(requestWire, responseWire);
  }

  private PinnedLaunchDescriptorSnapshot decodeStored(
      Record row,
      String expectedNamespace,
      String expectedRequestId,
      UUID expectedOwnerAttemptId,
      long expectedOwnerFence,
      GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot pinned) {
    Request expectedRequest = exactReplayRequest(pinned.result());
    StartSessionPostAuthorizationExecutionTuple tuple = expectedRequest.decodedTuple();
    byte[] requestWire = requiredBytes(row, "descriptor_request_wire");
    byte[] responseWire = requiredBytes(row, "descriptor_response_wire");
    if (!requiredText(row, "target_namespace").equals(expectedNamespace)
        || !requiredText(row, "control_plane_request_id").equals(expectedRequestId)
        || !requiredUuid(row, "canonical_tenant_id")
            .equals(pinned.result().association().canonicalTenantId())
        || !requiredUuid(row, "owner_attempt_id").equals(expectedOwnerAttemptId)
        || requiredLong(row, "owner_fence") != expectedOwnerFence
        || !requiredText(row, "association_request_digest").equals(pinned.requestDigest())
        || !requiredText(row, "association_response_digest").equals(pinned.responseDigest())
        || !requiredText(row, "descriptor_request_digest").equals(sha256(requestWire))
        || !requiredText(row, "descriptor_response_digest").equals(sha256(responseWire))) {
      throw new IllegalStateException(
          "Persisted StartSession descriptor pin differs from its original association "
              + "or owner claim");
    }
    if (!tuple.controlPlaneRequestId().equals(expectedRequestId)
        || !expectedRequest.targetNamespace().equals(expectedNamespace)
        || !expectedRequest.ownerAttemptId().equals(expectedOwnerAttemptId)
        || expectedRequest.ownerFence() != expectedOwnerFence) {
      throw new IllegalStateException(
          "Persisted StartSession descriptor pin request differs from its current owner claim");
    }
    requireWireBound(requestWire, responseWire);

    try {
      ReadStartSessionTemplateAssociationRequest requestMessage =
          ReadStartSessionTemplateAssociationRequest.parseFrom(requestWire);
      Request request = StartSessionLaunchDescriptorGrpcCodec.fromRequest(requestMessage);
      if (!request.equals(expectedRequest)
          || !Arrays.equals(
              StartSessionLaunchDescriptorGrpcCodec.toRequest(request).toByteArray(),
              requestWire)) {
        throw new IllegalStateException(
            "Persisted StartSession descriptor request differs from the retained exact selection");
      }
      ResolveStartSessionLaunchDescriptorResponse responseMessage =
          ResolveStartSessionLaunchDescriptorResponse.parseFrom(responseWire);
      Resolved resolved =
          StartSessionLaunchDescriptorGrpcCodec.fromResponse(expectedRequest, responseMessage);
      byte[] expectedAssociationWire =
          StartSessionTemplateAssociationReadGrpcCodec.toResponse(
                  exactReplayAssociation(
                      pinned.result(), expectedRequest, resolved.associationRead().phaseEpoch()))
              .toByteArray();
      byte[] retainedAssociationWire =
          StartSessionTemplateAssociationReadGrpcCodec.toResponse(resolved.associationRead())
              .toByteArray();
      if (!Arrays.equals(expectedAssociationWire, retainedAssociationWire)
          || !Arrays.equals(
              StartSessionLaunchDescriptorGrpcCodec.toResponse(
                      expectedRequest, resolved.associationRead(), resolved.outcome())
                  .toByteArray(),
              responseWire)) {
        throw new IllegalStateException(
            "Persisted StartSession descriptor response differs from its retained association");
      }
      return new PinnedLaunchDescriptorSnapshot(
          resolved,
          requestWire,
          responseWire,
          requiredText(row, "descriptor_request_digest"),
          requiredText(row, "descriptor_response_digest"),
          requiredInstant(row, "created_at"));
    } catch (InvalidProtocolBufferException | IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Persisted StartSession launch descriptor evidence is malformed", malformed);
    }
  }

  private static Result exactReplayAssociation(Result original, Request request, long phaseEpoch) {
    return new Result(
        request,
        original.association(),
        original.releaseBundle(),
        original.worldPublishedStartLocationEvidence(),
        phaseEpoch);
  }

  private static Request exactReplayRequest(Result original) {
    Association association = original.association();
    Request firstRequest = original.request();
    return new Request(
        firstRequest.schemaVersion(),
        firstRequest.targetNamespace(),
        firstRequest.readRequestId(),
        firstRequest.canonicalPostAuthorizationTuple(),
        firstRequest.ownerAttemptId(),
        firstRequest.ownerFence(),
        new ExactReplay(
            association.canonicalVersionId(),
            association.selectedCommitId(),
            association.publishWorkflowId(),
            association.associationDigest()));
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

  private Record selectPin(
      GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation continuation,
      boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + TABLE
            + " WHERE target_namespace = ? AND control_plane_request_id = ? "
            + "AND ?::timestamptz > clock_timestamp()"
            + (forUpdate ? " FOR UPDATE" : ""),
        continuation.targetNamespace(),
        continuation.controlPlaneRequestId(),
        originalAuthorizationExpiresAt(continuation));
  }

  private static String originalAuthorizationExpiresAt(
      GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation continuation) {
    byte[] exactTuple = continuation.postAuthorizationExecutionTuple();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(exactTuple);
    if (!Arrays.equals(tuple.canonicalBytes(), exactTuple)) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Evidence continuation tuple is not canonical");
    }
    return StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
        .authorizationExpiresAt();
  }

  private static void requireWireBound(byte[] requestWire, byte[] responseWire) {
    if (requestWire.length == 0
        || responseWire.length == 0
        || requestWire.length > MAX_REQUEST_WIRE_BYTES
        || responseWire.length > MAX_RESPONSE_WIRE_BYTES) {
      throw new StartSessionLaunchDescriptorConflictException(
          "Launch descriptor carrier exceeds the local fail-closed storage budget");
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
    if (value == null) {
      throw new IllegalStateException("Persisted descriptor pin is missing " + field);
    }
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row == null ? null : row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalStateException("Persisted descriptor pin is missing " + field);
    }
    return value;
  }

  private static long requiredLong(Record row, String field) {
    Long value = row == null ? null : row.get(field, Long.class);
    if (value == null) {
      throw new IllegalStateException("Persisted descriptor pin is missing " + field);
    }
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row == null ? null : row.get(field, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Persisted descriptor pin is missing " + field);
    }
    return value.clone();
  }

  private static Instant requiredInstant(Record row, String field) {
    java.time.OffsetDateTime value =
        row == null ? null : row.get(field, java.time.OffsetDateTime.class);
    if (value == null) {
      throw new IllegalStateException("Persisted descriptor pin is missing created_at");
    }
    return value.toInstant();
  }

  private record PreparedCandidate(byte[] requestWire, byte[] responseWire) {
    private PreparedCandidate {
      requestWire = requestWire.clone();
      responseWire = responseWire.clone();
    }

    @Override
    public byte[] requestWire() {
      return requestWire.clone();
    }

    @Override
    public byte[] responseWire() {
      return responseWire.clone();
    }
  }

  /** Retained typed outcome plus exact canonical wire evidence and local storage digests. */
  public record PinnedLaunchDescriptorSnapshot(
      Resolved resolved,
      byte[] requestWire,
      byte[] responseWire,
      String requestDigest,
      String responseDigest,
      Instant createdAt) {
    public PinnedLaunchDescriptorSnapshot {
      Objects.requireNonNull(resolved, "resolved is required");
      requestWire = Objects.requireNonNull(requestWire, "requestWire is required").clone();
      responseWire = Objects.requireNonNull(responseWire, "responseWire is required").clone();
      Objects.requireNonNull(requestDigest, "requestDigest is required");
      Objects.requireNonNull(responseDigest, "responseDigest is required");
      Objects.requireNonNull(createdAt, "createdAt is required");
    }

    @Override
    public byte[] requestWire() {
      return requestWire.clone();
    }

    @Override
    public byte[] responseWire() {
      return responseWire.clone();
    }
  }

  public static final class StartSessionLaunchDescriptorConflictException
      extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public StartSessionLaunchDescriptorConflictException(String message) {
      super(message);
    }
  }
}
