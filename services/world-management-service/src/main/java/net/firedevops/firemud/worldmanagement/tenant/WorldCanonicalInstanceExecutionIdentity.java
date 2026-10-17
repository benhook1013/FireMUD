package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;

/**
 * Exact World-local execution identity for one original human StartSession operation.
 *
 * <p>This is an integrity value, not by itself authenticated Account or Game Session evidence. The
 * explicit Account verifier builds the complete form from Account's returned first observation;
 * legacy forms remain read-only and cannot admit a fresh write. Its World execution fence is
 * allocated and retained by the World repository, independently of the supplied source fences.
 */
public final class WorldCanonicalInstanceExecutionIdentity {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final byte[] originalPostAuthorizationTuple;
  private final StartSessionPostAuthorizationExecutionTuple decodedTuple;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final UUID gameSessionOwnerAttemptId;
  private final UUID gameSessionOwnerMutationId;
  private final long gameSessionOwnerFence;
  private final Instant originalGameSessionLeaseExpiresAt;
  private final UUID originalAttemptReadRequestId;
  private final byte[] originalAttemptEvidenceBytes;
  private final String originalAttemptEvidenceDigest;
  private final UUID canonicalGameInstanceId;
  private final String preparationInputJson;
  private final String preparationInputDigest;
  private final Instant originalAuthorizationExpiry;

  public WorldCanonicalInstanceExecutionIdentity(
      byte[] originalPostAuthorizationTuple,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      UUID canonicalGameInstanceId,
      String exactPreparationInputJson) {
    this(
        originalPostAuthorizationTuple,
        accountWorldParticipationId,
        accountWorldParticipationFence,
        null,
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        null,
        canonicalGameInstanceId,
        exactPreparationInputJson,
        null);
  }

  /** Complete identity built only from Account's exact retained current-attempt response. */
  public WorldCanonicalInstanceExecutionIdentity(
      byte[] originalPostAuthorizationTuple,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      OriginalStartSessionCurrentAttemptEvidence.Result originalAttemptEvidence,
      UUID canonicalGameInstanceId,
      String exactPreparationInputJson) {
    this(
        originalPostAuthorizationTuple,
        accountWorldParticipationId,
        accountWorldParticipationFence,
        Objects.requireNonNull(gameSessionOwnerMutationId, "gameSessionOwnerMutationId"),
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        Objects.requireNonNull(originalAttemptEvidence, "originalAttemptEvidence"),
        canonicalGameInstanceId,
        exactPreparationInputJson,
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(originalAttemptEvidence)
            .toByteArray());
  }

  /** Complete identity retaining the exact Account-returned observation protobuf bytes. */
  public WorldCanonicalInstanceExecutionIdentity(
      byte[] originalPostAuthorizationTuple,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      OriginalStartSessionCurrentAttemptEvidence.Result originalAttemptEvidence,
      UUID canonicalGameInstanceId,
      String exactPreparationInputJson,
      byte[] originalAttemptEvidenceBytes) {
    if (originalPostAuthorizationTuple == null || originalPostAuthorizationTuple.length == 0) {
      throw new IllegalArgumentException("Exact original StartSession tuple is required");
    }
    this.originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
    decodedTuple =
        StartSessionPostAuthorizationExecutionTuple.decode(this.originalPostAuthorizationTuple);
    requireNonNil(accountWorldParticipationId, "accountWorldParticipationId");
    if (accountWorldParticipationFence <= 0L) {
      throw new IllegalArgumentException("accountWorldParticipationFence must be positive");
    }
    requireNonNil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    if (gameSessionOwnerMutationId != null) {
      requireNonNil(gameSessionOwnerMutationId, "gameSessionOwnerMutationId");
    }
    if (gameSessionOwnerFence <= 0L) {
      throw new IllegalArgumentException("gameSessionOwnerFence must be positive");
    }
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    if (exactPreparationInputJson == null || exactPreparationInputJson.isBlank()) {
      throw new IllegalArgumentException("Exact canonical World preparation input is required");
    }
    this.accountWorldParticipationId = accountWorldParticipationId;
    this.accountWorldParticipationFence = accountWorldParticipationFence;
    this.gameSessionOwnerAttemptId = gameSessionOwnerAttemptId;
    this.gameSessionOwnerMutationId = gameSessionOwnerMutationId;
    this.gameSessionOwnerFence = gameSessionOwnerFence;
    if (originalAttemptEvidence == null) {
      if (gameSessionOwnerMutationId != null || originalAttemptEvidenceBytes != null) {
        throw new IllegalArgumentException("Account original-attempt observation is incomplete");
      }
      this.originalGameSessionLeaseExpiresAt = null;
      this.originalAttemptReadRequestId = null;
      this.originalAttemptEvidenceBytes = null;
      this.originalAttemptEvidenceDigest = null;
    } else {
      if (gameSessionOwnerMutationId == null) {
        throw new IllegalArgumentException("Account original-attempt observation is incomplete");
      }
      var observationRequest = originalAttemptEvidence.request();
      if (!MessageDigest.isEqual(
              this.originalPostAuthorizationTuple,
              observationRequest.canonicalPostAuthorizationTuple())
          || !gameSessionOwnerMutationId.equals(observationRequest.expectedOwnerMutationId())
          || !gameSessionOwnerAttemptId.equals(observationRequest.expectedOwnerAttemptId())
          || gameSessionOwnerFence != observationRequest.expectedOwnerFence()
          || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
              originalAttemptEvidence.phaseState())
          || !decodedTuple
              .preAuthorizationTuple()
              .action()
              .scope()
              .targetNamespace()
              .equals(observationRequest.targetNamespace())
          || !StartSessionOperatorAction.OWNER_SERVICE.equals(
              decodedTuple.preAuthorizationTuple().targetOwner())
          || originalAttemptEvidenceBytes == null
          || !MessageDigest.isEqual(
              OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                      originalAttemptEvidence)
                  .toByteArray(),
              originalAttemptEvidenceBytes)) {
        throw new IllegalArgumentException(
            "Account original-attempt observation differs from the exact owner operation");
      }
      this.originalGameSessionLeaseExpiresAt = originalAttemptEvidence.originalLeaseExpiresAt();
      this.originalAttemptReadRequestId = observationRequest.readRequestId();
      this.originalAttemptEvidenceBytes = originalAttemptEvidenceBytes.clone();
      this.originalAttemptEvidenceDigest = digestBytes(originalAttemptEvidenceBytes);
    }
    this.canonicalGameInstanceId = canonicalGameInstanceId;
    this.preparationInputJson = exactPreparationInputJson;
    this.preparationInputDigest = digest(exactPreparationInputJson);

    StartSessionPreAuthorizationReservationTuple original = decodedTuple.preAuthorizationTuple();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(
            decodedTuple.preAuthorizationTuple().actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(original.targetOwner())) {
      throw new IllegalArgumentException(
          "World execution requires the original Game Session StartSession tuple");
    }
    originalAuthorizationExpiry =
        StartSessionAuthorityEvidenceBundle.decode(decodedTuple.authorityEvidenceBundleBytes())
            .expiresAt();
  }

  /** Compares this held identity to the exact existing World preparation input before any SQL. */
  public void requireMatches(Input input, String exactPreparationInputJson) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(exactPreparationInputJson, "exactPreparationInputJson");
    StartSessionPreAuthorizationReservationTuple original = decodedTuple.preAuthorizationTuple();
    StartSessionOperatorAction action = original.action();
    var world = input.gameSessionReadEvidence();
    if (!MessageDigest.isEqual(
            preparationInputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            exactPreparationInputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        || !canonicalGameInstanceId.equals(world.canonicalGameInstanceId())
        || !world.targetNamespace().equals(action.scope().targetNamespace())
        || !world.canonicalTenantId().equals(action.scope().tenantId())
        || !world.controlPlaneRequestId().equals(decodedTuple.controlPlaneRequestId())
        || !world.controlPlaneRequestId().equals(original.controlPlaneRequestId())
        || !gameSessionOwnerAttemptId.equals(input.gameSessionOwnerAttemptId())
        || gameSessionOwnerFence != input.gameSessionOwnerFence()
        || action.target().gameTemplateId()
            != input.completeLaunchBinding().evidence().descriptor().gameTemplateId()) {
      throw new IllegalArgumentException(
          "World execution identity differs from the exact original tuple, target, or preparation input");
    }
  }

  /** Exact owner observation checks required for every fresh materialization admission. */
  public void requireMatches(
      Input input,
      WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operationInput,
      String exactPreparationInputJson) {
    Objects.requireNonNull(operationInput, "operationInput");
    requireMatches(input, exactPreparationInputJson);
    operationInput.requireMatches(input, targetNamespace());
    if (!hasOriginalAttemptEvidence()
        || !MessageDigest.isEqual(
            originalPostAuthorizationTuple, operationInput.originalPostAuthorizationTuple())
        || !gameSessionOwnerMutationId.equals(operationInput.gameSessionOwnerMutationId())
        || !gameSessionOwnerAttemptId.equals(operationInput.gameSessionOwnerAttemptId())
        || gameSessionOwnerFence != operationInput.gameSessionOwnerFence()) {
      throw new IllegalArgumentException(
          "Account participation does not bind the exact original Game Session owner attempt");
    }
  }

  public boolean hasOriginalAttemptEvidence() {
    return gameSessionOwnerMutationId != null
        && originalGameSessionLeaseExpiresAt != null
        && originalAttemptReadRequestId != null
        && originalAttemptEvidenceBytes != null
        && originalAttemptEvidenceDigest != null;
  }

  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
  }

  public StartSessionPostAuthorizationExecutionTuple decodedTuple() {
    return decodedTuple;
  }

  public UUID accountWorldParticipationId() {
    return accountWorldParticipationId;
  }

  public long accountWorldParticipationFence() {
    return accountWorldParticipationFence;
  }

  public UUID gameSessionOwnerAttemptId() {
    return gameSessionOwnerAttemptId;
  }

  public UUID gameSessionOwnerMutationId() {
    return gameSessionOwnerMutationId;
  }

  public long gameSessionOwnerFence() {
    return gameSessionOwnerFence;
  }

  public Instant originalGameSessionLeaseExpiresAt() {
    return originalGameSessionLeaseExpiresAt;
  }

  public UUID originalAttemptReadRequestId() {
    return originalAttemptReadRequestId;
  }

  public byte[] originalAttemptEvidenceBytes() {
    return originalAttemptEvidenceBytes == null ? null : originalAttemptEvidenceBytes.clone();
  }

  public String originalAttemptEvidenceDigest() {
    return originalAttemptEvidenceDigest;
  }

  public UUID canonicalGameInstanceId() {
    return canonicalGameInstanceId;
  }

  public String targetNamespace() {
    return decodedTuple.preAuthorizationTuple().action().scope().targetNamespace();
  }

  public UUID canonicalTenantId() {
    return decodedTuple.preAuthorizationTuple().action().scope().tenantId();
  }

  public String controlPlaneRequestId() {
    return decodedTuple.controlPlaneRequestId();
  }

  public long gameTemplateId() {
    return decodedTuple.preAuthorizationTuple().action().target().gameTemplateId();
  }

  public String preparationInputDigest() {
    return preparationInputDigest;
  }

  public String preparationInputJson() {
    return preparationInputJson;
  }

  /**
   * Original bound expiry from the canonical Account evidence bundle; no World TTL is introduced.
   */
  public Instant originalAuthorizationExpiry() {
    return originalAuthorizationExpiry;
  }

  boolean exactlyMatches(WorldCanonicalInstanceExecutionIdentity other) {
    return other != null
        && MessageDigest.isEqual(
            originalPostAuthorizationTuple, other.originalPostAuthorizationTuple)
        && accountWorldParticipationId.equals(other.accountWorldParticipationId)
        && accountWorldParticipationFence == other.accountWorldParticipationFence
        && Objects.equals(gameSessionOwnerMutationId, other.gameSessionOwnerMutationId)
        && gameSessionOwnerAttemptId.equals(other.gameSessionOwnerAttemptId)
        && gameSessionOwnerFence == other.gameSessionOwnerFence
        && Objects.equals(
            originalGameSessionLeaseExpiresAt, other.originalGameSessionLeaseExpiresAt)
        && Objects.equals(originalAttemptReadRequestId, other.originalAttemptReadRequestId)
        && Arrays.equals(originalAttemptEvidenceBytes, other.originalAttemptEvidenceBytes)
        && Objects.equals(originalAttemptEvidenceDigest, other.originalAttemptEvidenceDigest)
        && canonicalGameInstanceId.equals(other.canonicalGameInstanceId)
        && preparationInputJson.equals(other.preparationInputJson);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WorldCanonicalInstanceExecutionIdentity that && exactlyMatches(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        Arrays.hashCode(originalPostAuthorizationTuple),
        accountWorldParticipationId,
        accountWorldParticipationFence,
        gameSessionOwnerMutationId,
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        originalGameSessionLeaseExpiresAt,
        originalAttemptReadRequestId,
        Arrays.hashCode(originalAttemptEvidenceBytes),
        originalAttemptEvidenceDigest,
        canonicalGameInstanceId,
        preparationInputJson);
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) throw new IllegalArgumentException(field + " must not be nil");
  }

  private static String digest(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String digestBytes(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
