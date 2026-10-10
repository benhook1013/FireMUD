package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;

/** Closed bounded protobuf encoding for Account's original StartSession protection producer. */
public final class AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec {
  public static final int MAX_WIRE_BYTES = 9 * 1024 * 1024;
  public static final int MAX_REQUEST_BYTES = 768 * 1024;

  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec() {}

  public static AcquireOriginalStartSessionAdmissionProtectionRequest toRequest(
      AccountStartSessionAdmissionProtectionAcquisitionInput input, String targetNamespace) {
    Objects.requireNonNull(input, "admission protection acquisition input is required");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw invalid("Target namespace must be one canonical DNS label");
    }
    input.requireTargetNamespace(targetNamespace);
    var result =
        AcquireOriginalStartSessionAdmissionProtectionRequest.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setTargetNamespace(targetNamespace)
            .setOriginalPostAuthorizationTuple(
                ByteString.copyFrom(input.originalPostAuthorizationTuple()))
            .setGameSessionOwnerMutationId(input.gameSessionOwnerMutationId().toString())
            .setGameSessionOwnerAttemptId(input.gameSessionOwnerAttemptId().toString())
            .setGameSessionOwnerFence(input.gameSessionOwnerFence())
            .setWorldHoldIdentity(ByteString.copyFrom(input.worldHoldIdentity().canonicalBytes()))
            .build();
    requireRequestBudget(result);
    return result;
  }

  /** Parses only after the receiver authenticates its exact same-namespace Game Session peer. */
  public static AccountStartSessionAdmissionProtectionAcquisitionInput fromRequest(
      AcquireOriginalStartSessionAdmissionProtectionRequest request, String expectedNamespace) {
    Objects.requireNonNull(request, "admission protection acquisition request is required");
    requireClosed(request, "request");
    requireRequestBudget(request);
    if (request.getSchemaVersion() != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace())) {
      throw invalid("Unsupported schema or target namespace");
    }
    byte[] tupleBytes = request.getOriginalPostAuthorizationTuple().toByteArray();
    byte[] holdBytes = request.getWorldHoldIdentity().toByteArray();
    if (tupleBytes.length == 0
        || tupleBytes.length
            > net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
                .MAX_CANONICAL_TUPLE_BYTES
        || holdBytes.length == 0
        || holdBytes.length > 64 * 1024) {
      throw invalid("Original tuple or complete World hold identity is missing or oversized");
    }
    AccountStartSessionAdmissionProtectionAcquisitionInput input =
        new AccountStartSessionAdmissionProtectionAcquisitionInput(
            tupleBytes,
            canonicalUuid(request.getGameSessionOwnerMutationId(), "gameSessionOwnerMutationId"),
            canonicalUuid(request.getGameSessionOwnerAttemptId(), "gameSessionOwnerAttemptId"),
            request.getGameSessionOwnerFence(),
            WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(holdBytes));
    input.requireTargetNamespace(expectedNamespace);
    if (!toRequest(input, expectedNamespace).equals(request)) {
      throw invalid("Admission protection request is not the exact canonical tuple");
    }
    return input;
  }

  public static AcquireOriginalStartSessionAdmissionProtectionResponse toResponse(
      AccountStartSessionAdmissionProtectionAcquisitionInput input,
      String targetNamespace,
      AccountStartSessionAdmissionProtectionEvidence evidence) {
    requireEvidenceMatchesInput(input, targetNamespace, evidence);
    var response =
        AcquireOriginalStartSessionAdmissionProtectionResponse.newBuilder()
            .setEvidenceCanonicalBytes(ByteString.copyFrom(evidence.canonicalBytes()))
            .build();
    requireResponseBudget(response);
    return response;
  }

  public static AccountStartSessionAdmissionProtectionEvidence fromResponse(
      AccountStartSessionAdmissionProtectionAcquisitionInput input,
      String targetNamespace,
      AcquireOriginalStartSessionAdmissionProtectionResponse response) {
    Objects.requireNonNull(input, "admission protection acquisition input is required");
    Objects.requireNonNull(response, "admission protection acquisition response is required");
    requireClosed(response, "response");
    requireResponseBudget(response);
    byte[] canonicalEvidence = response.getEvidenceCanonicalBytes().toByteArray();
    AccountStartSessionAdmissionProtectionEvidence evidence =
        AccountStartSessionAdmissionProtectionEvidence.decode(canonicalEvidence);
    requireEvidenceMatchesInput(input, targetNamespace, evidence);
    return evidence;
  }

  private static void requireEvidenceMatchesInput(
      AccountStartSessionAdmissionProtectionAcquisitionInput input,
      String targetNamespace,
      AccountStartSessionAdmissionProtectionEvidence evidence) {
    Objects.requireNonNull(input, "admission protection acquisition input is required");
    Objects.requireNonNull(evidence, "admission protection evidence is required");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw invalid("Target namespace must be one canonical DNS label");
    }
    input.requireTargetNamespace(targetNamespace);
    var retained = evidence.request();
    if (!Arrays.equals(
            input.originalPostAuthorizationTuple(), retained.originalPostAuthorizationTupleBytes())
        || !input.gameSessionOwnerMutationId().equals(retained.gameSessionOwnerMutationId())
        || !input.gameSessionOwnerAttemptId().equals(retained.gameSessionOwnerAttemptId())
        || input.gameSessionOwnerFence() != retained.gameSessionOwnerFence()
        || !MessageDigest.isEqual(
            input.worldHoldIdentity().canonicalBytes(), retained.worldAdmissionHoldIdentityBytes())
        || !retained
            .originalTuple()
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(targetNamespace)) {
      throw invalid("Account evidence changed the exact original acquisition binding");
    }
  }

  private static void requireRequestBudget(
      AcquireOriginalStartSessionAdmissionProtectionRequest request) {
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || request.getSerializedSize() > MAX_WIRE_BYTES) {
      throw invalid("Admission protection request exceeds its wire budget");
    }
  }

  private static void requireResponseBudget(
      AcquireOriginalStartSessionAdmissionProtectionResponse response) {
    if (response.getSerializedSize() > MAX_WIRE_BYTES
        || response.getEvidenceCanonicalBytes().isEmpty()
        || response.getEvidenceCanonicalBytes().size()
            > AccountStartSessionAdmissionProtectionEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("Admission protection evidence is missing or exceeds its wire budget");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Admission protection " + label + " contains unsupported fields");
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
      throw invalid(field + " must be a canonical non-nil UUID");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
