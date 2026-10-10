package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;

/** Closed protobuf adapter for Account's historical World participation read. */
public final class AccountStartSessionWorldParticipationHistoricalReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
          + StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
          + 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountStartSessionWorldParticipationHistoricalReadGrpcCodec() {}

  public static ReadHistoricalStartSessionWorldParticipationRequest toRequest(
      AccountStartSessionWorldParticipationHistoricalReadRequest request) {
    Objects.requireNonNull(request, "historical participation request is required");
    var result =
        ReadHistoricalStartSessionWorldParticipationRequest.newBuilder()
            .setSchemaVersion(
                AccountStartSessionWorldParticipationHistoricalReadRequest.SCHEMA_VERSION)
            .setReadRequestId(request.readRequestId().toString())
            .setTargetNamespace(request.targetNamespace())
            .setAccountWorldParticipationId(request.accountWorldParticipationId().toString())
            .setAccountWorldParticipationFence(request.accountWorldParticipationFence())
            .build();
    if (result.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw new IllegalArgumentException("Historical participation request exceeds its wire limit");
    }
    return result;
  }

  public static AccountStartSessionWorldParticipationHistoricalReadRequest fromRequest(
      ReadHistoricalStartSessionWorldParticipationRequest request) {
    Objects.requireNonNull(request, "historical participation request is required");
    requireClosedMessage(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || request.getSchemaVersion()
            != AccountStartSessionWorldParticipationHistoricalReadRequest.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported or oversized historical participation request");
    }
    return new AccountStartSessionWorldParticipationHistoricalReadRequest(
        canonicalUuid(request.getReadRequestId(), "readRequestId"),
        request.getTargetNamespace(),
        canonicalUuid(request.getAccountWorldParticipationId(), "accountWorldParticipationId"),
        request.getAccountWorldParticipationFence());
  }

  public static ReadHistoricalStartSessionWorldParticipationResponse toResponse(
      AccountStartSessionWorldParticipationHistoricalReadRequest request,
      AccountStartSessionWorldParticipationHistoricalReadEvidence evidence) {
    Objects.requireNonNull(evidence, "historical participation evidence is required");
    requireSameRequest(request, evidence.request());
    var response =
        ReadHistoricalStartSessionWorldParticipationResponse.newBuilder()
            .setRequest(toRequest(request))
            .setOriginalPostAuthorizationTuple(
                ByteString.copyFrom(evidence.originalPostAuthorizationTuple()))
            .setGameSessionOwnerAttemptId(evidence.gameSessionOwnerAttemptId().toString())
            .setGameSessionOwnerFence(evidence.gameSessionOwnerFence())
            .setCanonicalGameInstanceId(evidence.canonicalGameInstanceId().toString())
            .setPreparationInputJson(evidence.preparationInputJson())
            .setPreparationInputDigest(evidence.preparationInputDigest())
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) {
      throw new IllegalArgumentException(
          "Historical participation response exceeds its wire limit");
    }
    return response;
  }

  public static AccountStartSessionWorldParticipationHistoricalReadEvidence fromResponse(
      AccountStartSessionWorldParticipationHistoricalReadRequest request,
      ReadHistoricalStartSessionWorldParticipationResponse response) {
    Objects.requireNonNull(request, "historical participation request is required");
    Objects.requireNonNull(response, "historical participation response is required");
    requireClosedMessage(response, "response");
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES || !response.hasRequest()) {
      throw new IllegalArgumentException("Missing or oversized historical participation response");
    }
    AccountStartSessionWorldParticipationHistoricalReadRequest echoedRequest =
        fromRequest(response.getRequest());
    requireSameRequest(request, echoedRequest);
    byte[] originalTuple = response.getOriginalPostAuthorizationTuple().toByteArray();
    if (originalTuple.length == 0
        || originalTuple.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw new IllegalArgumentException("Original StartSession tuple is missing or oversized");
    }
    if (response.getPreparationInputJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Historical preparation input exceeds its byte limit");
    }
    return new AccountStartSessionWorldParticipationHistoricalReadEvidence(
        echoedRequest,
        originalTuple,
        canonicalUuid(response.getGameSessionOwnerAttemptId(), "gameSessionOwnerAttemptId"),
        response.getGameSessionOwnerFence(),
        canonicalUuid(response.getCanonicalGameInstanceId(), "canonicalGameInstanceId"),
        response.getPreparationInputJson(),
        response.getPreparationInputDigest());
  }

  private static void requireSameRequest(
      AccountStartSessionWorldParticipationHistoricalReadRequest expected,
      AccountStartSessionWorldParticipationHistoricalReadRequest actual) {
    Objects.requireNonNull(expected, "historical participation request is required");
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(
          "Account changed the exact historical participation request");
    }
  }

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static void requireClosedMessage(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "Account historical participation " + label + " contains unsupported fields");
    }
  }
}
