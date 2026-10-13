package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalResponse;

/** Closed wire mapping for the distinct Account-facing original-admission terminal read. */
public final class OriginalStartSessionAdmissionTerminalGrpcCodec {
  public static final int MAX_REQUEST_BYTES =
      OriginalStartSessionAdmissionTerminalRequest.MAX_CANONICAL_BYTES + 16;
  public static final int MAX_OWNER_PROOF_BYTES =
      OriginalStartSessionAdmissionTerminalResult.MAX_CANONICAL_BYTES;
  public static final int MAX_RESPONSE_BYTES = MAX_REQUEST_BYTES + MAX_OWNER_PROOF_BYTES + 16;

  private OriginalStartSessionAdmissionTerminalGrpcCodec() {}

  public static ReadOriginalStartSessionAdmissionTerminalRequest toRequest(
      OriginalStartSessionAdmissionTerminalRequest request) {
    Objects.requireNonNull(request, "request is required");
    var wire =
        ReadOriginalStartSessionAdmissionTerminalRequest.newBuilder()
            .setSchemaVersion(OriginalStartSessionAdmissionTerminalRequest.SCHEMA_VERSION)
            .setCanonicalAccountProtectionEvidence(ByteString.copyFrom(request.canonicalBytes()))
            .build();
    requirePositiveBound(wire.getSerializedSize(), MAX_REQUEST_BYTES, "terminal-read request");
    return wire;
  }

  /** Decodes only a closed, bounded selector; caller authentication is a separate GS boundary. */
  public static OriginalStartSessionAdmissionTerminalRequest fromRequest(
      ReadOriginalStartSessionAdmissionTerminalRequest request) {
    Objects.requireNonNull(request, "request is required");
    requirePositiveBound(request.getSerializedSize(), MAX_REQUEST_BYTES, "terminal-read request");
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != OriginalStartSessionAdmissionTerminalRequest.SCHEMA_VERSION
        || request.getCanonicalAccountProtectionEvidence().isEmpty()) {
      throw new IllegalArgumentException("Closed complete schema-1 terminal-read request required");
    }
    OriginalStartSessionAdmissionTerminalRequest decoded =
        new OriginalStartSessionAdmissionTerminalRequest(
            request.getCanonicalAccountProtectionEvidence().toByteArray());
    if (!toRequest(decoded).equals(request)) {
      throw new IllegalArgumentException("Terminal-read request is not canonical");
    }
    return decoded;
  }

  public static ReadOriginalStartSessionAdmissionTerminalResponse toResponse(
      OriginalStartSessionAdmissionTerminalResult result) {
    Objects.requireNonNull(result, "result is required");
    var response =
        ReadOriginalStartSessionAdmissionTerminalResponse.newBuilder()
            .setRequest(toRequest(result.request()))
            .setCanonicalGameSessionOwnerProof(ByteString.copyFrom(result.canonicalBytes()))
            .build();
    requirePositiveBound(
        response.getSerializedSize(), MAX_RESPONSE_BYTES, "terminal-read response");
    return response;
  }

  public static OriginalStartSessionAdmissionTerminalResult fromResponse(
      OriginalStartSessionAdmissionTerminalRequest expectedRequest,
      ReadOriginalStartSessionAdmissionTerminalResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest is required");
    Objects.requireNonNull(response, "response is required");
    requirePositiveBound(
        response.getSerializedSize(), MAX_RESPONSE_BYTES, "terminal-read response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || response.getCanonicalGameSessionOwnerProof().isEmpty()
        || response.getCanonicalGameSessionOwnerProof().size() > MAX_OWNER_PROOF_BYTES) {
      throw new IllegalArgumentException("Closed complete terminal-read response required");
    }
    OriginalStartSessionAdmissionTerminalRequest echoed = fromRequest(response.getRequest());
    if (!expectedRequest.equals(echoed)) {
      throw new IllegalArgumentException(
          "Terminal-read response changed the complete Account protection evidence");
    }
    byte[] proofBytes = response.getCanonicalGameSessionOwnerProof().toByteArray();
    var ownerProof = GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(proofBytes);
    OriginalStartSessionAdmissionTerminalResult result =
        new OriginalStartSessionAdmissionTerminalResult(echoed, ownerProof);
    if (!Arrays.equals(proofBytes, result.canonicalBytes())) {
      throw new IllegalArgumentException("Owner proof bytes are not canonical");
    }
    return result;
  }

  private static void requirePositiveBound(int size, int maximum, String field) {
    if (size <= 0 || size > maximum) {
      throw new IllegalArgumentException(field + " is empty or exceeds its byte bound");
    }
  }
}
