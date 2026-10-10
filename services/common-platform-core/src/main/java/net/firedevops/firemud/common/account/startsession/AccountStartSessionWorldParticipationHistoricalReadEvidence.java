package net.firedevops.firemud.common.account.startsession;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest;

/**
 * Account-derived immutable historical participation fields for exact World reconciliation.
 *
 * <p>This carrier is not currentness, admission, producer authentication, or renewed permission.
 */
public final class AccountStartSessionWorldParticipationHistoricalReadEvidence {
  private final AccountStartSessionWorldParticipationHistoricalReadRequest request;
  private final byte[] originalPostAuthorizationTuple;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
  private final UUID canonicalGameInstanceId;
  private final String preparationInputJson;
  private final String preparationInputDigest;

  public AccountStartSessionWorldParticipationHistoricalReadEvidence(
      AccountStartSessionWorldParticipationHistoricalReadRequest request,
      byte[] originalPostAuthorizationTuple,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      UUID canonicalGameInstanceId,
      String preparationInputJson,
      String preparationInputDigest) {
    this.request = Objects.requireNonNull(request, "historical participation request is required");
    Objects.requireNonNull(originalPostAuthorizationTuple, "original tuple is required");
    if (originalPostAuthorizationTuple.length == 0
        || originalPostAuthorizationTuple.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw new IllegalArgumentException("Original StartSession tuple is missing or oversized");
    }
    this.originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
    this.gameSessionOwnerAttemptId =
        Objects.requireNonNull(gameSessionOwnerAttemptId, "Game Session attempt is required");
    if (gameSessionOwnerFence <= 0L) {
      throw new IllegalArgumentException("Game Session owner fence must be positive");
    }
    this.gameSessionOwnerFence = gameSessionOwnerFence;
    this.canonicalGameInstanceId =
        Objects.requireNonNull(canonicalGameInstanceId, "canonical Game Instance ID is required");
    this.preparationInputJson =
        Objects.requireNonNull(preparationInputJson, "exact preparation input is required");
    this.preparationInputDigest =
        Objects.requireNonNull(preparationInputDigest, "preparation input digest is required");

    // Reuse the existing closed World identity validator without aliasing its terminal-read wire
    // type or claiming that this historical evidence admits a World execution.
    WorldStartSessionExecutionTerminalReadRequest exactIdentity =
        new WorldStartSessionExecutionTerminalReadRequest(
            request.readRequestId(),
            request.targetNamespace(),
            this.originalPostAuthorizationTuple,
            request.accountWorldParticipationId(),
            request.accountWorldParticipationFence(),
            this.gameSessionOwnerAttemptId,
            this.gameSessionOwnerFence,
            this.canonicalGameInstanceId,
            this.preparationInputJson);
    if (!exactIdentity.preparationInputDigest().equals(this.preparationInputDigest)) {
      throw new IllegalArgumentException(
          "preparationInputDigest differs from the exact retained preparation input");
    }
    if (this.preparationInputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException(
          "Historical World preparation input exceeds its byte limit");
    }
  }

  public AccountStartSessionWorldParticipationHistoricalReadRequest request() {
    return request;
  }

  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
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

  /** Returns the retained input byte-for-byte as text; it is never rewritten during recovery. */
  public String preparationInputJson() {
    return preparationInputJson;
  }

  public String preparationInputDigest() {
    return preparationInputDigest;
  }

  @Override
  public String toString() {
    return "AccountStartSessionWorldParticipationHistoricalReadEvidence[request="
        + request
        + ", preparationInput=<redacted>]";
  }
}
