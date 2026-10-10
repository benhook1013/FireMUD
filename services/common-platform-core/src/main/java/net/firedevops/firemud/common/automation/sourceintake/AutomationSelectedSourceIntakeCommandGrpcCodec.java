package net.firedevops.firemud.common.automation.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceRequest;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence.Request;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;

/** Closed mapping for selected-source retention with exact Account and World evidence echoes. */
public final class AutomationSelectedSourceIntakeCommandGrpcCodec {
  private static final int MAX_REQUEST_FRAMING_BYTES = 64 * 1024;

  /** Authorization plus complete freeze request and acknowledgement Account bindings. */
  public static final int MAX_REQUEST_WIRE_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          + 2 * WorldSelectedDraftPublicationFreezeEvidence.Request.MAX_ACCOUNT_BINDING_BYTES
          + MAX_REQUEST_FRAMING_BYTES;

  /** Complete receipt, complete echoed request, and bounded outer response framing. */
  public static final int MAX_RESPONSE_WIRE_BYTES =
      AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES
          + MAX_REQUEST_WIRE_BYTES
          + MAX_REQUEST_FRAMING_BYTES;

  private AutomationSelectedSourceIntakeCommandGrpcCodec() {}

  public static RetainSelectedSourceRequest toRequest(Request request) {
    Objects.requireNonNull(request, "selected-source command request is required");
    byte[] authorizationBytes = request.originalIntakeAuthorizationBinding();
    if (authorizationBytes.length == 0
        || authorizationBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Account authorization exceeds its 16 MiB limit");
    }
    var freeze = request.freezeEvidence();
    var wire =
        RetainSelectedSourceRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setTransportRequestId(request.transportRequestId().toString())
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(authorizationBytes))
            .setIntakeAuthorizationDigest(request.intakeAuthorizationDigest())
            .setFreezeRequest(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freeze.request()))
            .setFreezeAcknowledgement(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freeze.acknowledgement()))
            .build();
    requireWithinBudget(wire, MAX_REQUEST_WIRE_BYTES, "request");
    return wire;
  }

  /** Decode after the receiver authenticates the exact same-namespace Game Design peer. */
  public static Request fromRequest(RetainSelectedSourceRequest wire) {
    requireKnownAndWithinBudget(wire, MAX_REQUEST_WIRE_BYTES, "request");
    if (!wire.hasFreezeRequest() || !wire.hasFreezeAcknowledgement()) {
      throw invalid("Complete original World freeze request and acknowledgement are required");
    }
    UUID transportRequestId = canonicalUuid(wire.getTransportRequestId());
    int authorizationSize = wire.getOriginalIntakeAuthorizationBinding().size();
    if (authorizationSize == 0
        || authorizationSize > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Account authorization size is invalid");
    }
    byte[] authorizationBytes = wire.getOriginalIntakeAuthorizationBinding().toByteArray();
    SelectedOwnerIntakeAuthorizationBinding authorization;
    try {
      authorization = SelectedOwnerIntakeAuthorizationBinding.fromStored(authorizationBytes);
    } catch (RuntimeException malformed) {
      throw invalid("Original Account authorization is invalid", malformed);
    }
    if (!authorization.digest().equals(wire.getIntakeAuthorizationDigest())) {
      throw invalid("Original Account authorization digest differs");
    }
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(wire.getFreezeRequest());
    WorldSelectedDraftPublicationFreezeEvidence freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            freezeRequest, wire.getFreezeAcknowledgement());
    Request request =
        new Request(
            wire.getSchemaVersion(),
            wire.getTargetNamespace(),
            transportRequestId,
            authorization,
            freezeEvidence);
    if (!toRequest(request).equals(wire)) {
      throw invalid("Selected-source command request is not an exact canonical echo");
    }
    return request;
  }

  public static RetainSelectedSourceResponse toResponse(
      AutomationSelectedSourceIntakeCommandEvidence evidence) {
    Objects.requireNonNull(evidence, "selected-source command evidence is required");
    byte[] receiptBytes = evidence.receipt().canonicalBytes();
    if (receiptBytes.length == 0
        || receiptBytes.length > AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Committed Automation receipt size is invalid");
    }
    var response =
        RetainSelectedSourceResponse.newBuilder()
            .setRequest(toRequest(evidence.request()))
            .setCommittedEmptyReceipt(ByteString.copyFrom(receiptBytes))
            .setReceiptDigest(evidence.receipt().receiptDigest())
            .build();
    requireWithinBudget(response, MAX_RESPONSE_WIRE_BYTES, "response");
    return response;
  }

  /** Requires the exact complete request echo and verifies the canonical retained receipt. */
  public static AutomationSelectedSourceIntakeCommandEvidence fromResponse(
      Request expected, RetainSelectedSourceResponse response) {
    Objects.requireNonNull(expected, "selected-source command request is required");
    requireKnownAndWithinBudget(response, MAX_RESPONSE_WIRE_BYTES, "response");
    if (!response.hasRequest()) {
      throw invalid("Exact selected-source command request echo is required");
    }
    RetainSelectedSourceRequest expectedWire = toRequest(expected);
    if (!response.getRequest().equals(expectedWire)) {
      throw invalid("Automation changed the exact selected-source command request");
    }
    int receiptSize = response.getCommittedEmptyReceipt().size();
    if (receiptSize == 0 || receiptSize > AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Missing or oversized committed Automation receipt");
    }
    byte[] receiptBytes = response.getCommittedEmptyReceipt().toByteArray();
    AutomationEmptySelectedSourceIntakeReceipt receipt;
    try {
      receipt = AutomationEmptySelectedSourceIntakeReceipt.fromStored(receiptBytes);
    } catch (RuntimeException malformed) {
      throw invalid("Committed Automation receipt is invalid", malformed);
    }
    if (!receipt.receiptDigest().equals(response.getReceiptDigest())) {
      throw invalid("Automation receipt digest differs");
    }
    return new AutomationSelectedSourceIntakeCommandEvidence(expected, receipt);
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw invalid("Canonical nonzero transport request UUID required");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid("Canonical nonzero transport request UUID required", malformed);
    }
  }

  private static void requireKnownAndWithinBudget(Message message, int budget, String label) {
    if (message == null || message.getSerializedSize() > budget) {
      throw invalid("Automation selected-source command " + label + " exceeds its wire budget");
    }
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown Automation selected-source command " + label + " fields");
    }
  }

  private static void requireWithinBudget(Message message, int budget, String label) {
    if (message.getSerializedSize() > budget) {
      throw invalid("Automation selected-source command " + label + " exceeds its wire budget");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
