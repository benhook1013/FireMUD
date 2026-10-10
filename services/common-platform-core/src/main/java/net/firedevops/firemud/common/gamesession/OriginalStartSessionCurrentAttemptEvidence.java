package net.firedevops.firemud.common.gamesession;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;

/** Observation-only evidence for one exact, currently leased original StartSession attempt. */
public final class OriginalStartSessionCurrentAttemptEvidence {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_REQUEST_BYTES =
      StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES + 512;
  public static final int MAX_PROJECTION_BYTES =
      StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES;
  public static final String PENDING_PHASE = "OWNER_EXECUTION_PENDING";

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private OriginalStartSessionCurrentAttemptEvidence() {}

  public static String projectionDigest(byte[] projection) {
    Objects.requireNonNull(projection, "Account redemption projection is required");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(projection));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Exact request selector; all byte arrays are defensively copied. */
  public static final class Request {
    private final UUID readRequestId;
    private final String targetNamespace;
    private final StartSessionPostAuthorizationExecutionTuple originalTuple;
    private final byte[] originalTupleBytes;
    private final UUID expectedOwnerAttemptId;
    private final UUID expectedOwnerMutationId;
    private final long expectedOwnerFence;

    public Request(
        UUID readRequestId,
        String targetNamespace,
        byte[] canonicalPostAuthorizationTuple,
        UUID expectedOwnerAttemptId,
        UUID expectedOwnerMutationId,
        long expectedOwnerFence) {
      this.readRequestId = requireUuid(readRequestId, "readRequestId");
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be a valid workload namespace");
      }
      this.targetNamespace = targetNamespace;
      Objects.requireNonNull(
          canonicalPostAuthorizationTuple, "complete original tuple is required");
      if (canonicalPostAuthorizationTuple.length == 0
          || canonicalPostAuthorizationTuple.length
              > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
        throw new IllegalArgumentException("Complete original tuple is empty or exceeds its bound");
      }
      this.originalTupleBytes = canonicalPostAuthorizationTuple.clone();
      this.originalTuple =
          StartSessionPostAuthorizationExecutionTuple.decode(this.originalTupleBytes);
      if (!Arrays.equals(this.originalTuple.canonicalBytes(), this.originalTupleBytes)
          || !targetNamespace.equals(
              this.originalTuple.preAuthorizationTuple().action().scope().targetNamespace())) {
        throw new IllegalArgumentException(
            "Original tuple must be canonical and target the exact requested namespace");
      }
      this.expectedOwnerAttemptId = requireUuid(expectedOwnerAttemptId, "ownerAttemptId");
      this.expectedOwnerMutationId = requireUuid(expectedOwnerMutationId, "ownerMutationId");
      if (expectedOwnerFence <= 0L) {
        throw new IllegalArgumentException("ownerFence must be positive");
      }
      this.expectedOwnerFence = expectedOwnerFence;
    }

    public UUID readRequestId() {
      return readRequestId;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public StartSessionPostAuthorizationExecutionTuple originalTuple() {
      return originalTuple;
    }

    public byte[] canonicalPostAuthorizationTuple() {
      return originalTupleBytes.clone();
    }

    public UUID expectedOwnerAttemptId() {
      return expectedOwnerAttemptId;
    }

    public UUID expectedOwnerMutationId() {
      return expectedOwnerMutationId;
    }

    public long expectedOwnerFence() {
      return expectedOwnerFence;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Request that)) {
        return false;
      }
      return expectedOwnerFence == that.expectedOwnerFence
          && readRequestId.equals(that.readRequestId)
          && targetNamespace.equals(that.targetNamespace)
          && Arrays.equals(originalTupleBytes, that.originalTupleBytes)
          && expectedOwnerAttemptId.equals(that.expectedOwnerAttemptId)
          && expectedOwnerMutationId.equals(that.expectedOwnerMutationId);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              readRequestId,
              targetNamespace,
              expectedOwnerAttemptId,
              expectedOwnerMutationId,
              expectedOwnerFence);
      return 31 * result + Arrays.hashCode(originalTupleBytes);
    }
  }

  /** Exact successful point-in-time response, including the original unchanged lease expiry. */
  public static final class Result {
    private final Request request;
    private final Instant originalLeaseExpiresAt;
    private final byte[] accountRedemptionProjection;

    public Result(Request request, Instant originalLeaseExpiresAt, byte[] projection) {
      this.request = Objects.requireNonNull(request, "request is required");
      this.originalLeaseExpiresAt =
          Objects.requireNonNull(originalLeaseExpiresAt, "original lease expiry is required");
      Objects.requireNonNull(projection, "attached Account projection is required");
      if (projection.length == 0 || projection.length > MAX_PROJECTION_BYTES) {
        throw new IllegalArgumentException(
            "Attached Account projection is empty or exceeds its bound");
      }
      this.accountRedemptionProjection = projection.clone();
    }

    public Request request() {
      return request;
    }

    public String phaseState() {
      return PENDING_PHASE;
    }

    public Instant originalLeaseExpiresAt() {
      return originalLeaseExpiresAt;
    }

    public byte[] accountRedemptionProjection() {
      return accountRedemptionProjection.clone();
    }

    public String accountRedemptionProjectionDigest() {
      return projectionDigest(accountRedemptionProjection);
    }
  }

  private static UUID requireUuid(UUID value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a canonical non-nil UUID");
    }
    return value;
  }

  static boolean isCanonicalDigest(String value) {
    return value != null && SHA256.matcher(value).matches();
  }
}
