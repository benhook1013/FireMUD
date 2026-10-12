package net.firedevops.firemud.common.account.startsession;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;

/** Exact original-operation identity for one historical Account World participation lookup. */
public final class AccountStartSessionWorldParticipationHistoricalReadRequest {
  public static final int SCHEMA_VERSION = 1;

  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final UUID readRequestId;
  private final String targetNamespace;
  private final byte[] originalPostAuthorizationTuple;
  private final StartSessionPostAuthorizationExecutionTuple decodedOriginalTuple;

  public AccountStartSessionWorldParticipationHistoricalReadRequest(
      UUID readRequestId, String targetNamespace, byte[] originalPostAuthorizationTuple) {
    this.readRequestId = requireNonNil(readRequestId, "readRequestId");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    this.targetNamespace = targetNamespace;
    Objects.requireNonNull(originalPostAuthorizationTuple, "original tuple is required");
    if (originalPostAuthorizationTuple.length == 0
        || originalPostAuthorizationTuple.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw new IllegalArgumentException(
          "Complete original StartSession tuple is empty or oversized");
    }
    this.originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
    this.decodedOriginalTuple =
        StartSessionPostAuthorizationExecutionTuple.decode(this.originalPostAuthorizationTuple);
    if (!Arrays.equals(
            this.originalPostAuthorizationTuple, this.decodedOriginalTuple.canonicalBytes())
        || !StartSessionOperatorAction.ACTION_FAMILY.equals(
            this.decodedOriginalTuple.preAuthorizationTuple().actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(
            this.decodedOriginalTuple.preAuthorizationTuple().targetOwner())
        || !targetNamespace.equals(
            this.decodedOriginalTuple.preAuthorizationTuple().action().scope().targetNamespace())) {
      throw new IllegalArgumentException(
          "Complete canonical tuple must identify the original StartSession for this namespace");
    }

    if (this.readRequestId.equals(this.decodedOriginalTuple.reservationOwnerId())) {
      throw new IllegalArgumentException(
          "readRequestId must be fresh and distinct from the reservation owner");
    }
  }

  public UUID readRequestId() {
    return readRequestId;
  }

  public String targetNamespace() {
    return targetNamespace;
  }

  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
  }

  public StartSessionPostAuthorizationExecutionTuple decodedOriginalTuple() {
    return decodedOriginalTuple;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof AccountStartSessionWorldParticipationHistoricalReadRequest that)) {
      return false;
    }
    return readRequestId.equals(that.readRequestId)
        && targetNamespace.equals(that.targetNamespace)
        && Arrays.equals(originalPostAuthorizationTuple, that.originalPostAuthorizationTuple);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(readRequestId, targetNamespace)
        + Arrays.hashCode(originalPostAuthorizationTuple);
  }

  @Override
  public String toString() {
    return "AccountStartSessionWorldParticipationHistoricalReadRequest[readRequestId="
        + readRequestId
        + ", targetNamespace="
        + targetNamespace
        + ", originalTuple=<redacted>]";
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
    return value;
  }
}
