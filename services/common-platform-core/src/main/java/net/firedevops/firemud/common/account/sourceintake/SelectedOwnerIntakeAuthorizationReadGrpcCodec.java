package net.firedevops.firemud.common.account.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerIntakeAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed HELD-read wire mapping with full request-echo validation. */
public final class SelectedOwnerIntakeAuthorizationReadGrpcCodec {
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;

  private SelectedOwnerIntakeAuthorizationReadGrpcCodec() {}

  public static ReadHeldSelectedOwnerIntakeAuthorizationRequest toRequest(
      SelectedOwnerIntakeAuthorizationReadEvidence.Request request) {
    Objects.requireNonNull(request, "authorization-read request is required");
    byte[] bindingBytes = request.binding().canonicalBytes();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Selected-owner authorization exceeds its 16 MiB limit");
    }
    var wire =
        ReadHeldSelectedOwnerIntakeAuthorizationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setIntendedReader(request.intendedReader())
            .setRetentionPurpose(request.retentionPurpose())
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(bindingBytes))
            .setIntakeAuthorizationDigest(request.binding().digest())
            .build();
    requireWithinBudget(wire.getSerializedSize());
    return wire;
  }

  public static SelectedOwnerIntakeAuthorizationReadEvidence.Request fromRequest(
      ReadHeldSelectedOwnerIntakeAuthorizationRequest wire) {
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
        new SelectedOwnerIntakeAuthorizationReadEvidence.Request(
            wire.getSchemaVersion(),
            wire.getTargetNamespace(),
            UUID.fromString(wire.getReadRequestId()),
            binding);
    if (!request.intendedReader().equals(wire.getIntendedReader())
        || !request.retentionPurpose().equals(wire.getRetentionPurpose())
        || !toRequest(request).equals(wire)) {
      throw invalid("Selected-owner authorization reader, purpose, or request differs");
    }
    return request;
  }

  public static ReadHeldSelectedOwnerIntakeAuthorizationResponse toHeldResponse(
      SelectedOwnerIntakeAuthorizationReadEvidence.Request request) {
    var response =
        ReadHeldSelectedOwnerIntakeAuthorizationResponse.newBuilder()
            .setRequest(toRequest(request))
            .setHeld(true)
            .build();
    requireWithinBudget(response.getSerializedSize());
    return response;
  }

  public static SelectedOwnerIntakeAuthorizationReadEvidence fromResponse(
      SelectedOwnerIntakeAuthorizationReadEvidence.Request request,
      ReadHeldSelectedOwnerIntakeAuthorizationResponse response) {
    Objects.requireNonNull(request, "authorization-read request is required");
    requireKnownAndWithinBudget(response);
    if (!response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || !response.getHeld()) {
      throw invalid("Exact held selected-owner authorization response required");
    }
    return new SelectedOwnerIntakeAuthorizationReadEvidence(request);
  }

  private static void requireKnownAndWithinBudget(Message wire) {
    if (wire == null || wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw invalid("Missing or oversized selected-owner authorization-read message");
    if (!wire.getUnknownFields().asMap().isEmpty())
      throw invalid("Unknown selected-owner authorization-read fields");
  }

  private static void requireWithinBudget(int size) {
    if (size > MAX_WIRE_BYTES)
      throw invalid("Selected-owner authorization-read message exceeds 24 MiB");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
