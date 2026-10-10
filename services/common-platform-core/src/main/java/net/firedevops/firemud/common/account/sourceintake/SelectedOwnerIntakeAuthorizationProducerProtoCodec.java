package net.firedevops.firemud.common.account.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerOwner;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence.Request;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence.Result;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;

/** Closed request and exact authorization response mapping for the selected-owner producer. */
public final class SelectedOwnerIntakeAuthorizationProducerProtoCodec {
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;
  public static final int MAX_AUTHORIZATION_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES;

  private SelectedOwnerIntakeAuthorizationProducerProtoCodec() {}

  public static SelectedOwnerIntakeAuthorizationProducerRequest toRequest(Request request) {
    Objects.requireNonNull(request, "producer request is required");
    byte[] selection = request.selectedDraftBinding();
    if (selection.length == 0
        || selection.length
            > SelectedOwnerIntakeAuthorizationProducerEvidence.MAX_SELECTION_BYTES) {
      throw invalid("Selected Draft binding exceeds the 4 MiB scope limit");
    }
    var wire =
        SelectedOwnerIntakeAuthorizationProducerRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setTransportRequestId(request.transportRequestId().toString())
            .setIntakeRequestId(request.intakeRequestId().toString())
            .setOwner(toWire(request.owner()))
            .setSelectedDraftBinding(ByteString.copyFrom(selection))
            .build();
    requireWithinBudget(wire.getSerializedSize());
    return wire;
  }

  public static Request fromRequest(SelectedOwnerIntakeAuthorizationProducerRequest wire) {
    requireKnownAndWithinBudget(wire, "request");
    DraftAuthorizationFenceBinding.canonicalUuid(wire.getTransportRequestId());
    DraftAuthorizationFenceBinding.canonicalUuid(wire.getIntakeRequestId());
    byte[] selection = wire.getSelectedDraftBinding().toByteArray();
    if (selection.length == 0
        || selection.length
            > SelectedOwnerIntakeAuthorizationProducerEvidence.MAX_SELECTION_BYTES) {
      throw invalid("Selected Draft binding size is invalid");
    }
    Request request =
        new Request(
            wire.getSchemaVersion(),
            wire.getTargetNamespace(),
            UUID.fromString(wire.getTransportRequestId()),
            UUID.fromString(wire.getIntakeRequestId()),
            fromWire(wire.getOwner()),
            selection);
    if (!Arrays.equals(selection, request.selectedDraftBinding())
        || !toRequest(request).equals(wire)) {
      throw invalid("Selected Draft binding or request is noncanonical");
    }
    return request;
  }

  public static SelectedOwnerIntakeAuthorizationProducerResponse toResponse(Result result) {
    Objects.requireNonNull(result, "Account authorization result is required");
    byte[] binding = result.canonicalBindingBytes();
    if (binding.length == 0 || binding.length > MAX_AUTHORIZATION_BYTES) {
      throw invalid("Complete authorization binding exceeds its 16 MiB limit");
    }
    var response =
        SelectedOwnerIntakeAuthorizationProducerResponse.newBuilder()
            .setRequest(toRequest(result.request()))
            .setIntakeAuthorizationBinding(ByteString.copyFrom(binding))
            .setIntakeAuthorizationDigest(result.digest())
            .build();
    requireWithinBudget(response.getSerializedSize());
    return response;
  }

  public static Result fromResponse(
      Request request, SelectedOwnerIntakeAuthorizationProducerResponse response) {
    Objects.requireNonNull(request, "producer request is required");
    requireKnownAndWithinBudget(response, "response");
    if (!response.hasRequest()) throw invalid("Complete request echo is required");
    requireKnownAndWithinBudget(response.getRequest(), "echoed request");
    if (!toRequest(request).equals(response.getRequest())) {
      throw invalid("Account changed the selected-owner producer request");
    }
    byte[] bindingBytes = response.getIntakeAuthorizationBinding().toByteArray();
    if (bindingBytes.length == 0 || bindingBytes.length > MAX_AUTHORIZATION_BYTES) {
      throw invalid("Complete authorization binding size is invalid");
    }
    SelectedOwnerIntakeAuthorizationBinding binding;
    try {
      binding = SelectedOwnerIntakeAuthorizationBinding.fromStored(bindingBytes);
    } catch (RuntimeException malformed) {
      throw invalid("Complete authorization binding is malformed or noncanonical");
    }
    if (!Arrays.equals(bindingBytes, binding.canonicalBytes())
        || !binding.digest().equals(response.getIntakeAuthorizationDigest())) {
      throw invalid("Complete authorization binding or SHA-256 digest differs");
    }
    return new Result(request, binding, response.getIntakeAuthorizationDigest());
  }

  private static SelectedOwnerIntakeAuthorizationProducerOwner toWire(Owner owner) {
    return switch (owner) {
      case ENTITY_MANAGEMENT ->
          SelectedOwnerIntakeAuthorizationProducerOwner
              .SELECTED_OWNER_INTAKE_AUTHORIZATION_PRODUCER_OWNER_ENTITY_MANAGEMENT;
      case AUTOMATION_SCRIPTING ->
          SelectedOwnerIntakeAuthorizationProducerOwner
              .SELECTED_OWNER_INTAKE_AUTHORIZATION_PRODUCER_OWNER_AUTOMATION_SCRIPTING;
      default -> throw invalid("Unsupported selected-owner intake authorization owner");
    };
  }

  private static Owner fromWire(SelectedOwnerIntakeAuthorizationProducerOwner owner) {
    return switch (owner) {
      case SELECTED_OWNER_INTAKE_AUTHORIZATION_PRODUCER_OWNER_ENTITY_MANAGEMENT ->
          Owner.ENTITY_MANAGEMENT;
      case SELECTED_OWNER_INTAKE_AUTHORIZATION_PRODUCER_OWNER_AUTOMATION_SCRIPTING ->
          Owner.AUTOMATION_SCRIPTING;
      default -> throw invalid("Unsupported or unspecified selected-owner intake owner");
    };
  }

  private static void requireKnownAndWithinBudget(Message wire, String label) {
    if (wire == null || wire.getSerializedSize() > MAX_WIRE_BYTES) {
      throw invalid("Missing or oversized selected-owner producer " + label);
    }
    if (!wire.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown selected-owner producer " + label + " fields");
    }
  }

  private static void requireWithinBudget(int size) {
    if (size > MAX_WIRE_BYTES) {
      throw invalid("Selected-owner producer message exceeds its 24 MiB wire budget");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
