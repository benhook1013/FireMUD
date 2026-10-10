package net.firedevops.firemud.common.entity.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence.Request;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceRequest;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceResponse;

/** Closed Entity mapping with complete original Account/World evidence and exact receipt echo. */
public final class EntitySelectedSourceIntakeCommandGrpcCodec {
  private static final int MAX_REQUEST_FRAMING_BYTES = 64 * 1024;

  /** Original authorization, complete freeze request and acknowledgement, and outer framing. */
  public static final int MAX_REQUEST_WIRE_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          + 2 * WorldSelectedDraftPublicationFreezeEvidence.Request.MAX_ACCOUNT_BINDING_BYTES
          + MAX_REQUEST_FRAMING_BYTES;

  /** Full Entity receipt, full echoed request and bounded response framing. */
  public static final int MAX_RESPONSE_WIRE_BYTES =
      EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES
          + MAX_REQUEST_WIRE_BYTES
          + MAX_REQUEST_FRAMING_BYTES;

  private EntitySelectedSourceIntakeCommandGrpcCodec() {}

  public static RetainSelectedEntitySourceRequest toRequest(Request request) {
    Objects.requireNonNull(request, "selected-source command request is required");
    byte[] authorizationBytes = request.originalIntakeAuthorizationBinding();
    if (authorizationBytes.length == 0
        || authorizationBytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original Account authorization exceeds its 16 MiB limit");
    }
    var freeze = request.freezeEvidence();
    var wire =
        RetainSelectedEntitySourceRequest.newBuilder()
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

  /** Decode only after the receiver authenticates the exact same-namespace Game Design peer. */
  public static Request fromRequest(RetainSelectedEntitySourceRequest wire) {
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
    final SelectedOwnerIntakeAuthorizationBinding authorization;
    try {
      authorization = SelectedOwnerIntakeAuthorizationBinding.fromStored(authorizationBytes);
    } catch (RuntimeException malformed) {
      throw invalid("Original Account authorization is invalid", malformed);
    }
    if (!authorization.digest().equals(wire.getIntakeAuthorizationDigest())) {
      throw invalid("Original Account authorization digest differs");
    }
    final WorldSelectedDraftPublicationFreezeEvidence freezeEvidence;
    try {
      var freezeRequest =
          WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(wire.getFreezeRequest());
      freezeEvidence =
          WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
              freezeRequest, wire.getFreezeAcknowledgement());
    } catch (RuntimeException malformed) {
      throw invalid("Complete original World freeze evidence is invalid", malformed);
    }
    final Request request;
    try {
      request =
          new Request(
              wire.getSchemaVersion(),
              wire.getTargetNamespace(),
              transportRequestId,
              authorization,
              freezeEvidence);
    } catch (RuntimeException malformed) {
      throw invalid("Entity selected-source command binding is invalid", malformed);
    }
    if (!toRequest(request).equals(wire)) {
      throw invalid("Selected-source command request is not an exact canonical echo");
    }
    return request;
  }

  public static RetainSelectedEntitySourceResponse toResponse(
      EntitySelectedSourceIntakeCommandEvidence evidence) {
    Objects.requireNonNull(evidence, "selected-source command evidence is required");
    byte[] receiptBytes = evidence.receipt().canonicalBytes();
    if (receiptBytes.length == 0
        || receiptBytes.length > EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Committed Entity receipt size is invalid");
    }
    var response =
        RetainSelectedEntitySourceResponse.newBuilder()
            .setRequest(toRequest(evidence.request()))
            .setCommittedEmptyReceipt(ByteString.copyFrom(receiptBytes))
            .setReceiptDigest(evidence.receipt().receiptDigest())
            .build();
    requireWithinBudget(response, MAX_RESPONSE_WIRE_BYTES, "response");
    return response;
  }

  /**
   * Requires the exact complete request echo and verifies the canonical retained Entity receipt.
   */
  public static EntitySelectedSourceIntakeCommandEvidence fromResponse(
      Request expected, RetainSelectedEntitySourceResponse response) {
    Objects.requireNonNull(expected, "selected-source command request is required");
    requireKnownAndWithinBudget(response, MAX_RESPONSE_WIRE_BYTES, "response");
    if (!response.hasRequest()) {
      throw invalid("Exact selected-source command request echo is required");
    }
    if (!response.getRequest().equals(toRequest(expected))) {
      throw invalid("Entity changed the exact selected-source command request");
    }
    int receiptSize = response.getCommittedEmptyReceipt().size();
    if (receiptSize == 0 || receiptSize > EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES) {
      throw invalid("Missing or oversized committed Entity receipt");
    }
    final EntityEmptySelectedSourceIntakeReceipt receipt;
    try {
      receipt =
          EntityEmptySelectedSourceIntakeReceipt.fromStored(
              response.getCommittedEmptyReceipt().toByteArray());
    } catch (RuntimeException malformed) {
      throw invalid("Committed Entity receipt is invalid", malformed);
    }
    if (!receipt.receiptDigest().equals(response.getReceiptDigest())) {
      throw invalid("Entity receipt digest differs");
    }
    try {
      return new EntitySelectedSourceIntakeCommandEvidence(expected, receipt);
    } catch (RuntimeException mismatch) {
      throw invalid("Entity receipt differs from the complete original command", mismatch);
    }
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

  private static void requireKnownAndWithinBudget(Message wire, int budget, String label) {
    if (wire == null || wire.getSerializedSize() > budget) {
      throw invalid("Entity selected-source command " + label + " exceeds its wire budget");
    }
    if (!wire.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown Entity selected-source command " + label + " fields");
    }
  }

  private static void requireWithinBudget(Message wire, int budget, String label) {
    if (wire.getSerializedSize() > budget) {
      throw invalid("Entity selected-source command " + label + " exceeds its wire budget");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
