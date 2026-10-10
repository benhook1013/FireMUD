package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;

/**
 * Exact World-local execution identity for one original human StartSession operation.
 *
 * <p>This is an integrity value, not authenticated Account or Game Session evidence. The only
 * production-safe preparation service still denies because no producer supplies it. Its World
 * execution fence is allocated and retained by the World repository, independently of the two
 * supplied source fences.
 */
public final class WorldCanonicalInstanceExecutionIdentity {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final byte[] originalPostAuthorizationTuple;
  private final StartSessionPostAuthorizationExecutionTuple decodedTuple;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
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
    this.gameSessionOwnerFence = gameSessionOwnerFence;
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
        || action.target().gameTemplateId()
            != input.completeLaunchBinding().evidence().descriptor().gameTemplateId()) {
      throw new IllegalArgumentException(
          "World execution identity differs from the exact original tuple, target, or preparation input");
    }
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

  public long gameSessionOwnerFence() {
    return gameSessionOwnerFence;
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
        && gameSessionOwnerAttemptId.equals(other.gameSessionOwnerAttemptId)
        && gameSessionOwnerFence == other.gameSessionOwnerFence
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
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
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
}
