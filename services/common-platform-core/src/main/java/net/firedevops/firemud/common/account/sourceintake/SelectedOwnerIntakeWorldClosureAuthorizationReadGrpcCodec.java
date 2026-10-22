package net.firedevops.firemud.common.account.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed protobuf encoding for Account's distinct World closure HELD read. */
public final class SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec {
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;

  private SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec() {}

  public static ReadHeldSelectedOwnerWorldClosureAuthorizationRequest toRequest(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request request) {
    Objects.requireNonNull(request, "World closure authorization-read request is required");
    byte[] bindingBytes = request.binding().canonicalBytes();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Selected-owner authorization exceeds its 16 MiB limit");
    }
    var wire =
        ReadHeldSelectedOwnerWorldClosureAuthorizationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setIntendedReader(request.intendedReader())
            .setClosureReadPurpose(request.closureReadPurpose())
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(bindingBytes))
            .setIntakeAuthorizationDigest(request.binding().digest())
            .build();
    requireWithinBudget(wire.getSerializedSize());
    return wire;
  }

  public static SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request fromRequest(
      ReadHeldSelectedOwnerWorldClosureAuthorizationRequest wire) {
    requireKnownAndWithinBudget(wire);
    DraftAuthorizationFenceBinding.canonicalUuid(wire.getReadRequestId());
    byte[] bindingBytes = wire.getOriginalIntakeAuthorizationBinding().toByteArray();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Selected-owner authorization size is invalid");
    }
    var binding = SelectedOwnerIntakeAuthorizationBinding.fromStored(bindingBytes);
    if (!binding.digest().equals(wire.getIntakeAuthorizationDigest()))
      throw invalid("Selected-owner authorization digest differs");
    var request =
        new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request(
            wire.getSchemaVersion(),
            wire.getTargetNamespace(),
            UUID.fromString(wire.getReadRequestId()),
            binding);
    if (!request.intendedReader().equals(wire.getIntendedReader())
        || !request.closureReadPurpose().equals(wire.getClosureReadPurpose())
        || !toRequest(request).equals(wire)) {
      throw invalid("Selected-owner World reader, purpose, or request differs");
    }
    return request;
  }

  public static ReadHeldSelectedOwnerWorldClosureAuthorizationResponse toHeldResponse(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request request) {
    var response =
        ReadHeldSelectedOwnerWorldClosureAuthorizationResponse.newBuilder()
            .setRequest(toRequest(request))
            .setHeld(true)
            .build();
    requireWithinBudget(response.getSerializedSize());
    return response;
  }

  public static SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence fromResponse(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request request,
      ReadHeldSelectedOwnerWorldClosureAuthorizationResponse response) {
    Objects.requireNonNull(request, "World closure authorization-read request is required");
    requireKnownAndWithinBudget(response);
    if (!response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || !response.getHeld()) {
      throw invalid("Exact held selected-owner World closure authorization response required");
    }
    return new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(request);
  }

  private static void requireKnownAndWithinBudget(Message wire) {
    if (wire == null || wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw invalid("Missing or oversized selected-owner World closure read message");
    if (!wire.getUnknownFields().asMap().isEmpty())
      throw invalid("Unknown selected-owner World closure read fields");
  }

  private static void requireWithinBudget(int size) {
    if (size > MAX_WIRE_BYTES)
      throw invalid("Selected-owner World closure read message exceeds 24 MiB");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
