package net.firedevops.firemud.common.authoring;

import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;

/** Closed request mapping and exact echo/outcome validation for authenticated World readback. */
public final class WorldDraftTerminalReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private WorldDraftTerminalReadGrpcCodec() {}

  public static ReadWorldDraftTerminalOutcomeRequest toRequest(
      WorldDraftTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadWorldDraftTerminalOutcomeRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalAccountBinding(
            com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()))
        .build();
  }

  /** Decodes only after the receiver has authenticated its exact same-namespace Account peer. */
  public static WorldDraftTerminalReadEvidence.Request fromRequest(
      ReadWorldDraftTerminalOutcomeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadWorldDraftTerminalOutcomeRequest");
    try {
      return new WorldDraftTerminalReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
          request.getOriginalAccountBinding().toByteArray());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("World terminal read request is invalid", exception);
    }
  }

  /** Emits only UNKNOWN or exact canonical World definitive-abort readback. */
  public static ReadWorldDraftTerminalOutcomeResponse toResponse(
      WorldDraftTerminalReadEvidence.Request request,
      Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(ownerReadback, "ownerReadback");
    var builder =
        ReadWorldDraftTerminalOutcomeResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalAccountBinding(
                com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()));
    if (ownerReadback.isEmpty()) {
      return builder
          .setStatus(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN)
          .build();
    }
    var readback = ownerReadback.orElseThrow();
    requireExactOwnerReadback(request, readback);
    return builder
        .setStatus(
            WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED)
        .setOwnerReadbackBytes(com.google.protobuf.ByteString.copyFrom(readback.canonicalBytes()))
        .build();
  }

  /** Validates the complete echo and independently framed World owner result. */
  public static WorldDraftTerminalReadEvidence fromResponse(
      WorldDraftTerminalReadEvidence.Request request,
      ReadWorldDraftTerminalOutcomeResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadWorldDraftTerminalOutcomeResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())) {
      throw new IllegalArgumentException("World terminal response changed the exact read request");
    }
    Optional<DraftAuthorizationFenceBinding.OwnerReadback> readback;
    switch (response.getStatus()) {
      case WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN -> {
        if (!response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("UNKNOWN World readback cannot carry a result");
        }
        readback = Optional.empty();
      }
      case WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED -> {
        if (response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("World abort readback bytes are required");
        }
        var decoded =
            DraftAuthorizationFenceBinding.OwnerReadback.fromStored(
                response.getOwnerReadbackBytes().toByteArray());
        requireExactOwnerReadback(request, decoded);
        readback = Optional.of(decoded);
      }
      case WORLD_DRAFT_TERMINAL_READ_STATUS_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Unsupported World terminal read status");
      default -> throw new IllegalArgumentException("Unsupported World terminal read status");
    }
    return new WorldDraftTerminalReadEvidence(request, readback);
  }

  private static void requireExactOwnerReadback(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback readback) {
    DraftAuthorizationFenceBinding binding = request.accountBinding();
    if (readback.owner() != Owner.WORLD || readback.outcome() != Outcome.DEFINITIVELY_ABORTED) {
      throw new IllegalArgumentException("World terminal response is not a definitive abort");
    }
    readback.requireBinding(binding);
    if (!readback.operationId().equals(binding.operationId())
        || !readback.commitId().equals(binding.commitId())
        || !readback.fenceId().equals(binding.fenceId())
        || !readback.inputDigest().equals(binding.inputDigest())
        || !Arrays.equals(readback.fullBinding(), request.originalAccountBinding())
        || readback.result().length == 0) {
      throw new IllegalArgumentException(
          "World terminal response differs from the complete original Account binding");
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
}
