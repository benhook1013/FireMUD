package net.firedevops.firemud.common.automation.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeTerminalResult;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed terminal mapping with an unchanged request echo and canonical receipt validation. */
public final class AutomationSelectedSourceIntakeTerminalReadGrpcCodec {
  private static final int MAX_REQUEST_FRAMING_BYTES = 64 * 1024;

  /** Full receipt plus a complete echoed 16 MiB Account binding and bounded protobuf framing. */
  public static final int MAX_WIRE_BYTES =
      AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES
          + SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          + MAX_REQUEST_FRAMING_BYTES;

  private AutomationSelectedSourceIntakeTerminalReadGrpcCodec() {}

  public static ReadSelectedSourceIntakeTerminalRequest toRequest(
      AutomationSelectedSourceIntakeTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "terminal-read request is required");
    byte[] bindingBytes = request.binding().canonicalBytes();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Automation authorization exceeds its 16 MiB limit");
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
    requireWithinBudget(wire.getSerializedSize());
    return wire;
  }

  public static AutomationSelectedSourceIntakeTerminalReadEvidence.Request fromRequest(
      ReadSelectedSourceIntakeTerminalRequest wire) {
    requireKnownAndWithinBudget(wire, "terminal-read request");
    DraftAuthorizationFenceBinding.canonicalUuid(wire.getReadRequestId());
    byte[] bindingBytes = wire.getOriginalIntakeAuthorizationBinding().toByteArray();
    if (bindingBytes.length == 0
        || bindingBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Automation authorization size is invalid");
    }
    var binding = SelectedOwnerIntakeAuthorizationBinding.fromStored(bindingBytes);
    if (!binding.digest().equals(wire.getIntakeAuthorizationDigest())) {
      throw invalid("Original Automation authorization digest differs");
    }
    var request =
        new AutomationSelectedSourceIntakeTerminalReadEvidence.Request(
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
      AutomationSelectedSourceIntakeTerminalReadEvidence evidence) {
    Objects.requireNonNull(evidence, "committed Automation terminal evidence is required");
    byte[] receiptBytes = evidence.receipt().canonicalBytes();
    if (receiptBytes.length == 0
        || receiptBytes.length > AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Committed Automation receipt size is invalid");
    }
    var response =
        ReadSelectedSourceIntakeTerminalResponse.newBuilder()
            .setRequest(toRequest(evidence.request()))
            .setResult(
                AutomationSelectedSourceIntakeTerminalResult
                    .AUTOMATION_SELECTED_SOURCE_INTAKE_TERMINAL_RESULT_COMMITTED_EMPTY)
            .setCommittedEmptyReceipt(ByteString.copyFrom(receiptBytes))
            .setReceiptDigest(evidence.receipt().receiptDigest())
            .build();
    requireWithinBudget(response.getSerializedSize());
    return response;
  }

  public static AutomationSelectedSourceIntakeTerminalReadEvidence fromResponse(
      AutomationSelectedSourceIntakeTerminalReadEvidence.Request request,
      ReadSelectedSourceIntakeTerminalResponse response) {
    Objects.requireNonNull(request, "terminal-read request is required");
    requireKnownAndWithinBudget(response, "terminal-read response");
    if (!response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || response.getResult()
            != AutomationSelectedSourceIntakeTerminalResult
                .AUTOMATION_SELECTED_SOURCE_INTAKE_TERMINAL_RESULT_COMMITTED_EMPTY) {
      throw invalid("Exact COMMITTED_EMPTY response and full request echo required");
    }
    byte[] receiptBytes = response.getCommittedEmptyReceipt().toByteArray();
    if (receiptBytes.length == 0
        || receiptBytes.length > AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Missing or oversized committed Automation receipt");
    }
    AutomationEmptySelectedSourceIntakeReceipt receipt =
        AutomationEmptySelectedSourceIntakeReceipt.fromStored(receiptBytes);
    if (!receipt.receiptDigest().equals(response.getReceiptDigest())) {
      throw invalid("Automation receipt digest differs");
    }
    return new AutomationSelectedSourceIntakeTerminalReadEvidence(request, receipt);
  }

  private static void requireKnownAndWithinBudget(Message wire, String label) {
    if (wire == null || wire.getSerializedSize() > MAX_WIRE_BYTES) {
      throw invalid("Missing or oversized Automation " + label);
    }
    if (!wire.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown Automation " + label + " fields");
    }
  }

  private static void requireWithinBudget(int size) {
    if (size > MAX_WIRE_BYTES) {
      throw invalid("Automation terminal-read message exceeds its derived wire budget");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
