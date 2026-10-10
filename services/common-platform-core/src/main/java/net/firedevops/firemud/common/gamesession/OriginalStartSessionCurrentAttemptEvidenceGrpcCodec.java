package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;

/** Closed schema-1 wire mapping for an observation-only live original-attempt read. */
public final class OriginalStartSessionCurrentAttemptEvidenceGrpcCodec {
  private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
  private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;
  private static final int MAX_TIMESTAMP_NANOS = 999_999_999;

  public static final int MAX_RESPONSE_BYTES =
      OriginalStartSessionCurrentAttemptEvidence.MAX_REQUEST_BYTES
          + OriginalStartSessionCurrentAttemptEvidence.MAX_PROJECTION_BYTES
          + 1024;

  private OriginalStartSessionCurrentAttemptEvidenceGrpcCodec() {}

  public static ReadOriginalStartSessionCurrentAttemptRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var wire =
        ReadOriginalStartSessionCurrentAttemptRequest.newBuilder()
            .setSchemaVersion(OriginalStartSessionCurrentAttemptEvidence.SCHEMA_VERSION)
            .setReadRequestId(request.readRequestId().toString())
            .setTargetNamespace(request.targetNamespace())
            .setCanonicalPostAuthorizationTuple(
                ByteString.copyFrom(request.canonicalPostAuthorizationTuple()))
            .setExpectedOwnerAttemptId(request.expectedOwnerAttemptId().toString())
            .setExpectedOwnerMutationId(request.expectedOwnerMutationId().toString())
            .setExpectedOwnerFence(request.expectedOwnerFence())
            .build();
    if (wire.getSerializedSize() > OriginalStartSessionCurrentAttemptEvidence.MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Current-attempt request exceeds its byte bound");
    }
    return wire;
  }

  /** Decodes only a closed exact selector; it does not authenticate the caller. */
  public static Request fromRequest(ReadOriginalStartSessionCurrentAttemptRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > OriginalStartSessionCurrentAttemptEvidence.MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion()
            != OriginalStartSessionCurrentAttemptEvidence.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Closed bounded schema-1 current-attempt request required");
    }
    try {
      Request decoded =
          new Request(
              canonicalUuid(request.getReadRequestId(), "read_request_id"),
              request.getTargetNamespace(),
              request.getCanonicalPostAuthorizationTuple().toByteArray(),
              canonicalUuid(request.getExpectedOwnerAttemptId(), "expected_owner_attempt_id"),
              canonicalUuid(request.getExpectedOwnerMutationId(), "expected_owner_mutation_id"),
              request.getExpectedOwnerFence());
      if (!toRequest(decoded).equals(request)) {
        throw new IllegalArgumentException("Current-attempt request is not canonical");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Exact canonical current-attempt request required", malformed);
    }
  }

  public static ReadOriginalStartSessionCurrentAttemptResponse toResponse(Result result) {
    Objects.requireNonNull(result, "result");
    var response =
        ReadOriginalStartSessionCurrentAttemptResponse.newBuilder()
            .setRequest(toRequest(result.request()))
            .setPhaseState(result.phaseState())
            .setOriginalLeaseExpiresAt(toTimestamp(result.originalLeaseExpiresAt()))
            .setAccountRedemptionProjection(
                ByteString.copyFrom(result.accountRedemptionProjection()))
            .setAccountRedemptionProjectionDigest(result.accountRedemptionProjectionDigest())
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Current-attempt response exceeds its byte bound");
    }
    return response;
  }

  public static Result fromResponse(
      Request expectedRequest, ReadOriginalStartSessionCurrentAttemptResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !response.hasOriginalLeaseExpiresAt()
        || !response.getOriginalLeaseExpiresAt().getUnknownFields().asMap().isEmpty()
        || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
            response.getPhaseState())
        || response.getAccountRedemptionProjection().isEmpty()) {
      throw new IllegalArgumentException(
          "Closed complete pending current-attempt response required");
    }
    Request echoed = fromRequest(response.getRequest());
    if (!expectedRequest.equals(echoed)) {
      throw new IllegalArgumentException("Current-attempt response changed the exact read request");
    }
    byte[] projection = response.getAccountRedemptionProjection().toByteArray();
    Result result =
        new Result(echoed, fromTimestamp(response.getOriginalLeaseExpiresAt()), projection);
    if (!OriginalStartSessionCurrentAttemptEvidence.isCanonicalDigest(
            response.getAccountRedemptionProjectionDigest())
        || !result
            .accountRedemptionProjectionDigest()
            .equals(response.getAccountRedemptionProjectionDigest())) {
      throw new IllegalArgumentException("Account projection digest differs from its exact bytes");
    }
    return result;
  }

  private static Timestamp toTimestamp(Instant instant) {
    long seconds = instant.getEpochSecond();
    int nanos = instant.getNano();
    requireTimestampRange(seconds, nanos);
    return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static Instant fromTimestamp(Timestamp timestamp) {
    requireTimestampRange(timestamp.getSeconds(), timestamp.getNanos());
    try {
      return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Original lease timestamp is invalid", malformed);
    }
  }

  private static void requireTimestampRange(long seconds, int nanos) {
    if (seconds < MIN_TIMESTAMP_SECONDS
        || seconds > MAX_TIMESTAMP_SECONDS
        || nanos < 0
        || nanos > MAX_TIMESTAMP_NANOS) {
      throw new IllegalArgumentException("Original lease timestamp is outside the protobuf range");
    }
  }

  private static UUID canonicalUuid(String value, String name) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, name));
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(name + " must be canonical and non-nil");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " must be canonical and non-nil", invalid);
    }
  }
}
