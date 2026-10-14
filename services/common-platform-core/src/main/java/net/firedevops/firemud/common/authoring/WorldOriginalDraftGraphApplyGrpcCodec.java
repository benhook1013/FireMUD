package net.firedevops.firemud.common.authoring;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.worldmanagement.v1.ApplyOriginalDraftGraphRequest;
import net.firedevops.firemud.worldmanagement.v1.ApplyOriginalDraftGraphResponse;

/** Closed original-operation transport; there is no UNKNOWN or abort success response. */
public final class WorldOriginalDraftGraphApplyGrpcCodec {
  // Allow bounded protobuf framing and namespace overhead in addition to retained bytes.
  public static final int MAX_REQUEST_WIRE_BYTES =
      WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES + 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES
          + WorldOriginalDraftGraphApplyEvidence.MAX_OWNER_READBACK_BYTES
          + 1024;

  private WorldOriginalDraftGraphApplyGrpcCodec() {}

  public static ApplyOriginalDraftGraphRequest toRequest(
      WorldOriginalDraftGraphApplyEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    var wire =
        ApplyOriginalDraftGraphRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setOriginalAccountBinding(ByteString.copyFrom(request.originalAccountBinding()))
            .build();
    requireClosed(wire, MAX_REQUEST_WIRE_BYTES);
    return wire;
  }

  public static WorldOriginalDraftGraphApplyEvidence.Request fromRequest(
      ApplyOriginalDraftGraphRequest wire) {
    requireClosed(wire, MAX_REQUEST_WIRE_BYTES);
    return new WorldOriginalDraftGraphApplyEvidence.Request(
        wire.getSchemaVersion(),
        wire.getTargetNamespace(),
        wire.getOriginalAccountBinding().toByteArray());
  }

  public static ApplyOriginalDraftGraphResponse toResponse(
      WorldOriginalDraftGraphApplyEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback ownerReadback) {
    var result = new WorldOriginalDraftGraphApplyEvidence.Result(request, ownerReadback);
    var wire =
        ApplyOriginalDraftGraphResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setOriginalAccountBinding(ByteString.copyFrom(request.originalAccountBinding()))
            .setOwnerReadbackBytes(ByteString.copyFrom(result.ownerReadback().canonicalBytes()))
            .build();
    requireClosed(wire, MAX_RESPONSE_WIRE_BYTES);
    return wire;
  }

  public static WorldOriginalDraftGraphApplyEvidence.Result fromResponse(
      WorldOriginalDraftGraphApplyEvidence.Request request, ApplyOriginalDraftGraphResponse wire) {
    Objects.requireNonNull(request, "request");
    requireClosed(wire, MAX_RESPONSE_WIRE_BYTES);
    if (wire.getSchemaVersion() != request.schemaVersion()
        || !wire.getTargetNamespace().equals(request.targetNamespace())
        || !Arrays.equals(
            wire.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())) {
      throw new IllegalArgumentException("World original apply response changed the exact request");
    }
    byte[] bytes = wire.getOwnerReadbackBytes().toByteArray();
    WorldOriginalDraftGraphApplyEvidence.requireBytes(
        bytes, WorldOriginalDraftGraphApplyEvidence.MAX_OWNER_READBACK_BYTES);
    return new WorldOriginalDraftGraphApplyEvidence.Result(
        request, DraftAuthorizationFenceBinding.OwnerReadback.fromStored(bytes));
  }

  private static void requireClosed(Message wire, int maximum) {
    Objects.requireNonNull(wire, "wire");
    if (!wire.getUnknownFields().asMap().isEmpty() || wire.getSerializedSize() > maximum) {
      throw new IllegalArgumentException(
          "World original apply message has unsupported fields or size");
    }
  }
}
