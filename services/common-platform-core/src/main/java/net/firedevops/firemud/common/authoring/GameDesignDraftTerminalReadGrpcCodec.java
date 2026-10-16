package net.firedevops.firemud.common.authoring;

import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadStatus;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeResponse;

/** Closed request mapping and exact echo/outcome validation for authenticated Game Design reads. */
public final class GameDesignDraftTerminalReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private GameDesignDraftTerminalReadGrpcCodec() {}

  public static ReadGameDesignDraftTerminalOutcomeRequest toRequest(
      GameDesignDraftTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    byte[] binding = request.originalAccountBinding();
    requireBytes(binding.length, DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES, "binding");
    var wire = ReadGameDesignDraftTerminalOutcomeRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalAccountBinding(
            com.google.protobuf.ByteString.copyFrom(binding))
        .build();
    requireMessageSize(wire, DraftTerminalReadGrpcLimits.MAX_REQUEST_WIRE_BYTES, "request");
    return wire;
  }

  /** Decodes only after the receiver has authenticated its exact same-namespace Account peer. */
  public static GameDesignDraftTerminalReadEvidence.Request fromRequest(
      ReadGameDesignDraftTerminalOutcomeRequest request) {
    Objects.requireNonNull(request, "request");
    requireMessageSize(request, DraftTerminalReadGrpcLimits.MAX_REQUEST_WIRE_BYTES, "request");
    requireBytes(
        request.getOriginalAccountBinding().size(),
        DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES,
        "binding");
    requireNoUnknownFields(request, "ReadGameDesignDraftTerminalOutcomeRequest");
    try {
      return new GameDesignDraftTerminalReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
          request.getOriginalAccountBinding().toByteArray());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Game Design terminal read request is invalid", exception);
    }
  }

  /** Emits only UNKNOWN or an exact persisted Game Design terminal readback. */
  public static ReadGameDesignDraftTerminalOutcomeResponse toResponse(
      GameDesignDraftTerminalReadEvidence.Request request,
      Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(ownerReadback, "ownerReadback");
    byte[] binding = request.originalAccountBinding();
    requireBytes(binding.length, DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES, "binding");
    var builder =
        ReadGameDesignDraftTerminalOutcomeResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalAccountBinding(
                com.google.protobuf.ByteString.copyFrom(binding));
    if (ownerReadback.isEmpty()) {
      var response = builder
          .setStatus(
              GameDesignDraftTerminalReadStatus.GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_UNKNOWN)
          .build();
      requireMessageSize(
          response, DraftTerminalReadGrpcLimits.MAX_RESPONSE_WIRE_BYTES, "response");
      return response;
    }
    var readback = ownerReadback.orElseThrow();
    byte[] ownerReadbackBytes = readback.canonicalBytes();
    requireBytes(
        ownerReadbackBytes.length,
        DraftTerminalReadGrpcLimits.MAX_OWNER_READBACK_BYTES,
        "owner readback");
    requireExactOwnerReadback(request, readback);
    var status =
        readback.outcome() == Outcome.COMMITTED
            ? GameDesignDraftTerminalReadStatus.GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED
            : GameDesignDraftTerminalReadStatus
                .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED;
    var response = builder
        .setStatus(status)
        .setOwnerReadbackBytes(com.google.protobuf.ByteString.copyFrom(ownerReadbackBytes))
        .build();
    requireMessageSize(response, DraftTerminalReadGrpcLimits.MAX_RESPONSE_WIRE_BYTES, "response");
    return response;
  }

  /** Validates the complete echo and independently framed Game Design owner result. */
  public static GameDesignDraftTerminalReadEvidence fromResponse(
      GameDesignDraftTerminalReadEvidence.Request request,
      ReadGameDesignDraftTerminalOutcomeResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireMessageSize(response, DraftTerminalReadGrpcLimits.MAX_RESPONSE_WIRE_BYTES, "response");
    requireBytes(
        response.getOriginalAccountBinding().size(),
        DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES,
        "binding");
    requireBytes(
        response.getOwnerReadbackBytes().size(),
        DraftTerminalReadGrpcLimits.MAX_OWNER_READBACK_BYTES,
        "owner readback");
    requireNoUnknownFields(response, "ReadGameDesignDraftTerminalOutcomeResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())) {
      throw new IllegalArgumentException(
          "Game Design terminal response changed the exact read request");
    }
    Optional<DraftAuthorizationFenceBinding.OwnerReadback> readback;
    switch (response.getStatus()) {
      case GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_UNKNOWN -> {
        if (!response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("UNKNOWN Game Design readback cannot carry a result");
        }
        readback = Optional.empty();
      }
      case GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED,
          GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED -> {
        if (response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException(
              "Game Design terminal owner readback bytes are required");
        }
        var decoded =
            DraftAuthorizationFenceBinding.OwnerReadback.fromStored(
                response.getOwnerReadbackBytes().toByteArray());
        requireExactOwnerReadback(request, decoded);
        if ((response.getStatus()
                == GameDesignDraftTerminalReadStatus
                    .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED)
            != (decoded.outcome() == Outcome.COMMITTED)) {
          throw new IllegalArgumentException("Game Design status differs from the owner outcome");
        }
        readback = Optional.of(decoded);
      }
      case GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Unsupported Game Design terminal read status");
      default -> throw new IllegalArgumentException("Unsupported Game Design terminal read status");
    }
    return new GameDesignDraftTerminalReadEvidence(request, readback);
  }

  private static void requireExactOwnerReadback(
      GameDesignDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback readback) {
    DraftAuthorizationFenceBinding binding = request.accountBinding();
    if (readback.owner() != Owner.GAME_DESIGN
        || (readback.outcome() != Outcome.COMMITTED
            && readback.outcome() != Outcome.DEFINITIVELY_ABORTED)) {
      throw new IllegalArgumentException("Game Design response is not a supported terminal result");
    }
    readback.requireBinding(binding);
    if (!readback.operationId().equals(binding.operationId())
        || !readback.commitId().equals(binding.commitId())
        || !readback.fenceId().equals(binding.fenceId())
        || !readback.inputDigest().equals(binding.inputDigest())
        || !Arrays.equals(readback.fullBinding(), request.originalAccountBinding())
        || readback.result().length == 0) {
      throw new IllegalArgumentException(
          "Game Design terminal response differs from the complete original Account binding");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
  }

  private static void requireMessageSize(Message message, int maximum, String label) {
    if (message.getSerializedSize() > maximum) {
      throw new IllegalArgumentException(
          "Game Design terminal " + label + " exceeds its wire bound");
    }
  }

  private static void requireBytes(int size, int maximum, String label) {
    if (size > maximum) {
      throw new IllegalArgumentException(
          "Game Design terminal " + label + " exceeds its byte bound");
    }
  }
}
