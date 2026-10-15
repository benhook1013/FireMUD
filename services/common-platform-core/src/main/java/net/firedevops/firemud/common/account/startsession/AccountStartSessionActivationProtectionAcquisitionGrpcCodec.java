package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;

/** Closed bounded protobuf encoding for Account's original StartSession activation protection. */
public final class AccountStartSessionActivationProtectionAcquisitionGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 65 * 1024 * 1024;

  /** Existing complete evidence cap plus only protobuf tag/length framing headroom. */
  public static final int MAX_RESPONSE_WIRE_BYTES =
      AccountStartSessionActivationProtectionEvidence.MAX_CANONICAL_BYTES + 64;

  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountStartSessionActivationProtectionAcquisitionGrpcCodec() {}

  public static AcquireOriginalStartSessionActivationProtectionRequest toRequest(
      AccountStartSessionActivationProtectionAcquisitionInput input, String targetNamespace) {
    Objects.requireNonNull(input, "activation protection acquisition input is required");
    requireNamespace(targetNamespace);
    input.requireTargetNamespace(targetNamespace);
    var request =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(targetNamespace)
            .addOriginalPostAuthorizationTuple(
                ByteString.copyFrom(input.originalPostAuthorizationTuple()))
            .addGameSessionOwnerMutationId(input.gameSessionOwnerMutationId().toString())
            .addGameSessionOwnerAttemptId(input.gameSessionOwnerAttemptId().toString())
            .addGameSessionOwnerFence(Long.toString(input.gameSessionOwnerFence()))
            .addActivationRequestId(input.worldActivationRequest().activationRequestId().toString())
            .addWorldPreparingEvidence(
                ByteString.copyFrom(input.worldActivationRequest().preparingEvidenceBytes()))
            .build();
    requireRequestBudget(request);
    return request;
  }

  /**
   * Parses only after the receiver authenticates its exact same-namespace World peer. Repeated
   * scalar fields let the generated object preserve and reject duplicate occurrences.
   */
  public static AccountStartSessionActivationProtectionAcquisitionInput fromRequest(
      AcquireOriginalStartSessionActivationProtectionRequest request, String expectedNamespace) {
    Objects.requireNonNull(request, "activation protection acquisition request is required");
    requireClosed(request, "request");
    requireRequestBudget(request);
    if (request.getSchemaVersionCount() != 1
        || request.getTargetNamespaceCount() != 1
        || request.getOriginalPostAuthorizationTupleCount() != 1
        || request.getGameSessionOwnerMutationIdCount() != 1
        || request.getGameSessionOwnerAttemptIdCount() != 1
        || request.getGameSessionOwnerFenceCount() != 1
        || request.getActivationRequestIdCount() != 1
        || request.getWorldPreparingEvidenceCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace(0))) {
      throw invalid("Unsupported schema, duplicate field, or target namespace");
    }

    byte[] tupleBytes = request.getOriginalPostAuthorizationTuple(0).toByteArray();
    byte[] preparingBytes = request.getWorldPreparingEvidence(0).toByteArray();
    if (tupleBytes.length == 0
        || tupleBytes.length
            > net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
                .MAX_CANONICAL_TUPLE_BYTES
        || preparingBytes.length == 0
        || preparingBytes.length
            > AccountStartSessionActivationProtectionRequest.MAX_WORLD_PREPARING_EVIDENCE_BYTES) {
      throw invalid("Original tuple or complete World PREPARING evidence is missing or oversized");
    }
    var input =
        new AccountStartSessionActivationProtectionAcquisitionInput(
            tupleBytes,
            canonicalUuid(request.getGameSessionOwnerMutationId(0), "gameSessionOwnerMutationId"),
            canonicalUuid(request.getGameSessionOwnerAttemptId(0), "gameSessionOwnerAttemptId"),
            canonicalPositiveLong(request.getGameSessionOwnerFence(0), "gameSessionOwnerFence"),
            new WorldCanonicalInstanceActivation.Request(
                canonicalUuid(request.getActivationRequestId(0), "activationRequestId"),
                net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
                    .fromStored(preparingBytes)));
    input.requireTargetNamespace(expectedNamespace);
    if (!toRequest(input, expectedNamespace).equals(request)) {
      throw invalid("Activation protection request is not the exact canonical tuple");
    }
    return input;
  }

  /**
   * Optional raw-wire parser for contexts that receive the protobuf frame directly. Generated gRPC
   * service methods receive a parsed message, so the server additionally uses repeated-field counts
   * to preserve duplicate detection at that boundary.
   */
  public static AccountStartSessionActivationProtectionAcquisitionInput fromRequestWireBytes(
      byte[] exactCanonicalWireBytes, String expectedNamespace) {
    Objects.requireNonNull(exactCanonicalWireBytes, "exact request wire bytes are required");
    if (exactCanonicalWireBytes.length == 0
        || exactCanonicalWireBytes.length > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("Activation protection request exceeds its wire budget");
    }
    try {
      var request =
          AcquireOriginalStartSessionActivationProtectionRequest.parseFrom(exactCanonicalWireBytes);
      AccountStartSessionActivationProtectionAcquisitionInput input =
          fromRequest(request, expectedNamespace);
      if (!Arrays.equals(
          exactCanonicalWireBytes, toRequest(input, expectedNamespace).toByteArray())) {
        throw invalid("Activation protection request wire bytes are not canonical");
      }
      return input;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("Activation protection request wire bytes are malformed", malformed);
    }
  }

  public static AcquireOriginalStartSessionActivationProtectionResponse toResponse(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String targetNamespace,
      AccountStartSessionActivationProtectionEvidence evidence) {
    requireEvidenceMatchesInput(input, targetNamespace, evidence);
    var response =
        AcquireOriginalStartSessionActivationProtectionResponse.newBuilder()
            .addEvidenceCanonicalBytes(ByteString.copyFrom(evidence.canonicalBytes()))
            .build();
    requireResponseBudget(response);
    return response;
  }

  /** Validates full canonical evidence and returns its first immutable receipt bytes unchanged. */
  public static AccountStartSessionActivationProtectionEvidence fromResponse(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String targetNamespace,
      AcquireOriginalStartSessionActivationProtectionResponse response) {
    Objects.requireNonNull(input, "activation protection acquisition input is required");
    Objects.requireNonNull(response, "activation protection acquisition response is required");
    requireClosed(response, "response");
    requireResponseBudget(response);
    if (response.getEvidenceCanonicalBytesCount() != 1) {
      throw invalid("Activation protection response must contain one evidence receipt");
    }
    AccountStartSessionActivationProtectionEvidence evidence =
        AccountStartSessionActivationProtectionEvidence.decode(
            response.getEvidenceCanonicalBytes(0).toByteArray());
    requireEvidenceMatchesInput(input, targetNamespace, evidence);
    return evidence;
  }

  public static AccountStartSessionActivationProtectionEvidence fromResponseWireBytes(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String targetNamespace,
      byte[] exactCanonicalWireBytes) {
    Objects.requireNonNull(exactCanonicalWireBytes, "response wire bytes are required");
    if (exactCanonicalWireBytes.length == 0
        || exactCanonicalWireBytes.length > MAX_RESPONSE_WIRE_BYTES) {
      throw invalid("Activation protection response exceeds its wire budget");
    }
    try {
      var response =
          AcquireOriginalStartSessionActivationProtectionResponse.parseFrom(
              exactCanonicalWireBytes);
      AccountStartSessionActivationProtectionEvidence evidence =
          fromResponse(input, targetNamespace, response);
      if (!Arrays.equals(
          exactCanonicalWireBytes, toResponse(input, targetNamespace, evidence).toByteArray())) {
        throw invalid("Activation protection response wire bytes are not canonical");
      }
      return evidence;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("Activation protection response wire bytes are malformed", malformed);
    }
  }

  private static void requireEvidenceMatchesInput(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String targetNamespace,
      AccountStartSessionActivationProtectionEvidence evidence) {
    Objects.requireNonNull(input, "activation protection acquisition input is required");
    Objects.requireNonNull(evidence, "activation protection evidence is required");
    requireNamespace(targetNamespace);
    input.requireTargetNamespace(targetNamespace);
    var retained = evidence.request();
    var retainedActivation = retained.worldActivationRequest();
    var requestedActivation = input.worldActivationRequest();
    if (!Arrays.equals(
            input.originalPostAuthorizationTuple(), retained.originalPostAuthorizationTupleBytes())
        || !input.gameSessionOwnerMutationId().equals(retained.gameSessionOwnerMutationId())
        || !input.gameSessionOwnerAttemptId().equals(retained.gameSessionOwnerAttemptId())
        || input.gameSessionOwnerFence() != retained.gameSessionOwnerFence()
        || !requestedActivation
            .activationRequestId()
            .equals(retainedActivation.activationRequestId())
        || !MessageDigest.isEqual(
            requestedActivation.canonicalRequestBytes(), retainedActivation.canonicalRequestBytes())
        || !targetNamespace.equals(
            retained.originalTuple().preAuthorizationTuple().action().scope().targetNamespace())) {
      throw invalid("Account evidence changed the exact original activation acquisition binding");
    }
  }

  private static void requireRequestBudget(
      AcquireOriginalStartSessionActivationProtectionRequest request) {
    if (request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("Activation protection request exceeds its wire budget");
    }
  }

  private static void requireResponseBudget(
      AcquireOriginalStartSessionActivationProtectionResponse response) {
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getEvidenceCanonicalBytesCount() != 1
        || response.getEvidenceCanonicalBytes(0).isEmpty()
        || response.getEvidenceCanonicalBytes(0).size()
            > AccountStartSessionActivationProtectionEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("Activation protection evidence is missing or exceeds its wire budget");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Activation protection " + label + " contains unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw invalid(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static long canonicalPositiveLong(String value, String field) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
      throw invalid(field + " must be canonical positive unsigned decimal");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw invalid(field + " exceeds the supported positive fence range", overflow);
    }
  }

  private static void requireNamespace(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw invalid("Target namespace must be one canonical DNS label");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
