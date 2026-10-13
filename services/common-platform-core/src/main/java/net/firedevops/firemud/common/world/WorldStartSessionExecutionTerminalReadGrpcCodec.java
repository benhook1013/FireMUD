package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalResponse;

/** Closed protobuf adapter for exact historical StartSession terminal readback. */
public final class WorldStartSessionExecutionTerminalReadGrpcCodec {
  /** Canonical terminal plus bounded protobuf framing and the fresh read UUID. */
  public static final int MAX_RESPONSE_BYTES =
      WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES + 1_024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private WorldStartSessionExecutionTerminalReadGrpcCodec() {}

  public static WorldStartSessionExecutionTerminalReadRequest fromRequest(
      ReadWorldStartSessionExecutionTerminalRequest request) {
    Objects.requireNonNull(request, "World StartSession terminal read request");
    requireNoUnknownFields(request, "request");
    return new WorldStartSessionExecutionTerminalReadRequest(
        canonicalUuid(request.getReadRequestId(), "readRequestId"),
        request.getTargetNamespace(),
        request.getOriginalPostAuthorizationTuple().toByteArray(),
        canonicalUuid(request.getAccountWorldParticipationId(), "accountWorldParticipationId"),
        request.getAccountWorldParticipationFence(),
        canonicalUuid(request.getGameSessionOwnerAttemptId(), "gameSessionOwnerAttemptId"),
        request.getGameSessionOwnerFence(),
        canonicalUuid(request.getCanonicalGameInstanceId(), "canonicalGameInstanceId"),
        request.getPreparationInputJson());
  }

  public static ReadWorldStartSessionExecutionTerminalRequest toRequest(
      WorldStartSessionExecutionTerminalReadRequest request) {
    Objects.requireNonNull(request, "World StartSession terminal read request");
    return ReadWorldStartSessionExecutionTerminalRequest.newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setOriginalPostAuthorizationTuple(
            ByteString.copyFrom(request.originalPostAuthorizationTuple()))
        .setAccountWorldParticipationId(request.accountWorldParticipationId().toString())
        .setAccountWorldParticipationFence(request.accountWorldParticipationFence())
        .setGameSessionOwnerAttemptId(request.gameSessionOwnerAttemptId().toString())
        .setGameSessionOwnerFence(request.gameSessionOwnerFence())
        .setCanonicalGameInstanceId(request.canonicalGameInstanceId().toString())
        .setPreparationInputJson(request.preparationInputJson())
        .build();
  }

  public static ReadWorldStartSessionExecutionTerminalResponse toResponse(
      WorldStartSessionExecutionTerminalReadRequest request,
      WorldStartSessionExecutionTerminal terminal) {
    requireSameIdentity(request, terminal);
    return ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setCanonicalTerminalBytes(ByteString.copyFrom(terminal.canonicalBytes()))
        .build();
  }

  public static WorldStartSessionExecutionTerminal fromResponse(
      WorldStartSessionExecutionTerminalReadRequest request,
      ReadWorldStartSessionExecutionTerminalResponse response) {
    Objects.requireNonNull(request, "World StartSession terminal read request");
    Objects.requireNonNull(response, "World StartSession terminal response");
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw invalid("World StartSession terminal response exceeds its wire limit");
    }
    requireNoUnknownFields(response, "response");
    UUID readRequestId = canonicalUuid(response.getReadRequestId(), "readRequestId");
    if (!request.readRequestId().equals(readRequestId)) {
      throw invalid("World changed the exact StartSession terminal read correlation");
    }
    byte[] bytes = response.getCanonicalTerminalBytes().toByteArray();
    if (bytes.length == 0
        || bytes.length > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw invalid("World StartSession terminal bytes are missing or exceed their limit");
    }
    WorldStartSessionExecutionTerminal terminal =
        WorldStartSessionExecutionTerminal.fromStored(bytes);
    requireSameIdentity(request, terminal);
    return terminal;
  }

  private static void requireSameIdentity(
      WorldStartSessionExecutionTerminalReadRequest request,
      WorldStartSessionExecutionTerminal terminal) {
    Objects.requireNonNull(request, "World StartSession terminal read request");
    Objects.requireNonNull(terminal, "World StartSession terminal");
    if (!Arrays.equals(
            request.originalPostAuthorizationTuple(), terminal.originalPostAuthorizationTuple())
        || !request.accountWorldParticipationId().equals(terminal.accountWorldParticipationId())
        || request.accountWorldParticipationFence() != terminal.accountWorldParticipationFence()
        || !request.gameSessionOwnerAttemptId().equals(terminal.gameSessionOwnerAttemptId())
        || request.gameSessionOwnerFence() != terminal.gameSessionOwnerFence()
        || !request.targetNamespace().equals(terminal.targetNamespace())
        || !request.canonicalTenantId().equals(terminal.canonicalTenantId())
        || !request.controlPlaneRequestId().equals(terminal.controlPlaneRequestId())
        || !request.canonicalGameInstanceId().equals(terminal.canonicalGameInstanceId())
        || !request.preparationInputDigest().equals(terminal.preparationInputDigest())
        || !request.preparationInputJson().equals(terminal.preparationInputJson())) {
      throw invalid("World terminal differs from the complete original StartSession identity");
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

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("World StartSession terminal " + label + " contains unsupported fields");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
