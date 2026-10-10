package net.firedevops.firemud.common.account.startsession;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact lookup identity for one historical Account World StartSession participation. */
public record AccountStartSessionWorldParticipationHistoricalReadRequest(
    UUID readRequestId,
    String targetNamespace,
    UUID accountWorldParticipationId,
    long accountWorldParticipationFence) {
  public static final int SCHEMA_VERSION = 1;

  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionWorldParticipationHistoricalReadRequest {
    requireNonNil(readRequestId, "readRequestId");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    Objects.requireNonNull(accountWorldParticipationId, "accountWorldParticipationId");
    if (NIL_UUID.equals(accountWorldParticipationId)) {
      throw new IllegalArgumentException(
          "accountWorldParticipationId must be a canonical non-nil UUID");
    }
    if (readRequestId.equals(accountWorldParticipationId)) {
      throw new IllegalArgumentException(
          "readRequestId must be fresh and distinct from the participation ID");
    }
    if (accountWorldParticipationFence <= 0L) {
      throw new IllegalArgumentException(
          "accountWorldParticipationFence must be a positive signed 64-bit integer");
    }
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
    return value;
  }
}
