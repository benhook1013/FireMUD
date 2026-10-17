package net.firedevops.firemud.common.entity.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeTerminalResult;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalResponse;

/** Closed terminal mapping with an unchanged request echo and canonical receipt validation. */
public final class EntitySelectedSourceIntakeTerminalReadGrpcCodec {
  private static final int MAX_REQUEST_FRAMING_BYTES = 64 * 1024;

  /** Full receipt plus the complete echoed 16 MiB Account binding and bounded protobuf framing. */
  public static final int MAX_REQUEST_WIRE_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + MAX_REQUEST_FRAMING_BYTES;

  public static final int MAX_WIRE_BYTES =
      EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES + MAX_REQUEST_WIRE_BYTES;

  private EntitySelectedSourceIntakeTerminalReadGrpcCodec() {}

  public static ReadSelectedSourceIntakeTerminalRequest toRequest(
      EntitySelectedSourceIntakeTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "terminal-read request is required");
    byte[] bindingBytes = request.binding().canonicalBytes();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Entity authorization exceeds its 16 MiB limit");
    }
    var wire =
        ReadSelectedSourceIntakeTerminalRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setIntendedReader(request.intendedReader())
            .setTerminalReadPurpose(request.terminalReadPurpose())
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(bindingBytes))
            .setIntakeAuthorizationDigest(request.binding().digest())
            .build();
    requireWithinBudget(wire.getSerializedSize(), MAX_REQUEST_WIRE_BYTES, "request");
    return wire;
  }

  public static EntitySelectedSourceIntakeTerminalReadEvidence.Request fromRequest(
      ReadSelectedSourceIntakeTerminalRequest wire) {
    requireKnownAndWithinBudget(wire, MAX_REQUEST_WIRE_BYTES, "request");
    DraftAuthorizationFenceBinding.canonicalUuid(wire.getReadRequestId());
    byte[] bindingBytes = wire.getOriginalIntakeAuthorizationBinding().toByteArray();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Entity authorization size is invalid");
    }
    var binding = SelectedOwnerIntakeAuthorizationBinding.fromStored(bindingBytes);
    if (!binding.digest().equals(wire.getIntakeAuthorizationDigest())) {
      throw invalid("Original Entity authorization digest differs");
    }
    var request =
        new EntitySelectedSourceIntakeTerminalReadEvidence.Request(
            wire.getSchemaVersion(),
            wire.getTargetNamespace(),
            UUID.fromString(wire.getReadRequestId()),
            binding);
    if (!request.intendedReader().equals(wire.getIntendedReader())
        || !request.terminalReadPurpose().equals(wire.getTerminalReadPurpose())
        || !toRequest(request).equals(wire)) {
      throw invalid("Terminal reader, purpose, or request differs");
    }
    return request;
  }

  public static ReadSelectedSourceIntakeTerminalResponse toResponse(
      EntitySelectedSourceIntakeTerminalReadEvidence evidence) {
    Objects.requireNonNull(evidence, "committed Entity terminal evidence is required");
    byte[] receiptBytes = evidence.receipt().canonicalBytes();
    if (receiptBytes.length == 0
        || receiptBytes.length > EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Committed Entity receipt size is invalid");
    }
    var response =
        ReadSelectedSourceIntakeTerminalResponse.newBuilder()
            .setRequest(toRequest(evidence.request()))
            .setResult(
                EntitySelectedSourceIntakeTerminalResult
                    .ENTITY_SELECTED_SOURCE_INTAKE_TERMINAL_RESULT_COMMITTED_EMPTY)
            .setCommittedEmptyReceipt(ByteString.copyFrom(receiptBytes))
            .setReceiptDigest(evidence.receipt().receiptDigest())
            .build();
    requireWithinBudget(response.getSerializedSize(), MAX_WIRE_BYTES, "response");
    return response;
  }

  public static EntitySelectedSourceIntakeTerminalReadEvidence fromResponse(
      EntitySelectedSourceIntakeTerminalReadEvidence.Request request,
      ReadSelectedSourceIntakeTerminalResponse response) {
    Objects.requireNonNull(request, "terminal-read request is required");
    requireKnownAndWithinBudget(response, MAX_WIRE_BYTES, "response");
    if (!response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || response.getResult()
            != EntitySelectedSourceIntakeTerminalResult
                .ENTITY_SELECTED_SOURCE_INTAKE_TERMINAL_RESULT_COMMITTED_EMPTY) {
      throw invalid("Exact COMMITTED_EMPTY response and full request echo required");
    }
    byte[] receiptBytes = response.getCommittedEmptyReceipt().toByteArray();
    if (receiptBytes.length == 0
        || receiptBytes.length > EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Missing or oversized committed Entity receipt");
    }
    EntityEmptySelectedSourceIntakeReceipt receipt =
        EntityEmptySelectedSourceIntakeReceipt.fromStored(receiptBytes);
    if (!receipt.receiptDigest().equals(response.getReceiptDigest())) {
      throw invalid("Entity receipt digest differs");
    }
    return new EntitySelectedSourceIntakeTerminalReadEvidence(request, receipt);
  }

  private static void requireKnownAndWithinBudget(Message wire, int maxBytes, String label) {
    if (wire == null || wire.getSerializedSize() > maxBytes) {
      throw invalid("Missing or oversized Entity terminal-read " + label);
    }
    if (!wire.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown Entity terminal-read " + label + " fields");
    }
  }

  private static void requireWithinBudget(int size, int maxBytes, String label) {
    if (size > maxBytes) {
      throw invalid("Entity terminal-read " + label + " exceeds its derived wire budget");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
