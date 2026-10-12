package net.firedevops.firemud.common.account.startsession;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest;

/** Account-derived immutable historical participation fields for exact World reconciliation. */
public final class AccountStartSessionWorldParticipationHistoricalReadEvidence {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountStartSessionWorldParticipationHistoricalReadRequest request;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
  private final UUID canonicalGameInstanceId;
  private final String preparationInputJson;
  private final String preparationInputDigest;

  public AccountStartSessionWorldParticipationHistoricalReadEvidence(
      AccountStartSessionWorldParticipationHistoricalReadRequest request,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      UUID canonicalGameInstanceId,
      String preparationInputJson,
      String preparationInputDigest) {
    this.request = Objects.requireNonNull(request, "historical participation request is required");
    this.accountWorldParticipationId =
        requireNonNil(accountWorldParticipationId, "accountWorldParticipationId");
    if (accountWorldParticipationFence <= 0L) {
      throw new IllegalArgumentException("Account World participation fence must be positive");
    }
    this.accountWorldParticipationFence = accountWorldParticipationFence;
    this.gameSessionOwnerAttemptId =
        requireNonNil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    if (gameSessionOwnerFence <= 0L) {
      throw new IllegalArgumentException("Game Session owner fence must be positive");
    }
    this.gameSessionOwnerFence = gameSessionOwnerFence;
    this.canonicalGameInstanceId =
        requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
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
            request.originalPostAuthorizationTuple(),
            this.accountWorldParticipationId,
            this.accountWorldParticipationFence,
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
        + ", accountWorldParticipationId="
        + accountWorldParticipationId
        + ", preparationInput=<redacted>]";
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
    return value;
  }
}
