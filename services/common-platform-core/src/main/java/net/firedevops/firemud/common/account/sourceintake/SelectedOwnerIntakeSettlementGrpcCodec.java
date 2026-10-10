package net.firedevops.firemud.common.account.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence.Request;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence.Result;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Strict closed-schema mapping for the standalone selected-owner settlement command. */
public final class SelectedOwnerIntakeSettlementGrpcCodec {
  public static final int MAX_REQUEST_BYTES =
      SelectedOwnerIntakeSettlementEvidence.MAX_REQUEST_BYTES;
  public static final int MAX_RESPONSE_BYTES =
      SelectedOwnerIntakeSettlementEvidence.MAX_RESPONSE_BYTES;

  private SelectedOwnerIntakeSettlementGrpcCodec() {}

  public static SelectedOwnerIntakeSettlementRequest toRequest(Request request) {
    if (request == null) throw invalid("Settlement request is required");
    byte[] authorization = request.authorizationBindingBytes();
    if (authorization.length == 0
        || authorization.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original authorization exceeds its 16 MiB bound");
    }
    var wire =
        SelectedOwnerIntakeSettlementRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setTransportRequestId(request.transportRequestId().toString())
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(authorization))
            .setIntakeAuthorizationDigest(request.authorizationBindingDigest())
            .build();
    requireWithinBudget(wire.getSerializedSize(), MAX_REQUEST_BYTES, "request");
    return wire;
  }

  public static Request fromRequest(SelectedOwnerIntakeSettlementRequest wire) {
    requireKnownAndWithinBudget(wire, MAX_REQUEST_BYTES, "request");
    if (wire.getOriginalIntakeAuthorizationBinding().size()
        > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES) {
      throw invalid("Original authorization exceeds its 16 MiB bound");
    }
    DraftAuthorizationFenceBinding.canonicalUuid(wire.getTransportRequestId());
    byte[] authorizationBytes = wire.getOriginalIntakeAuthorizationBinding().toByteArray();
    if (authorizationBytes.length == 0) throw invalid("Original authorization is required");
    SelectedOwnerIntakeAuthorizationBinding authorization;
    try {
      authorization = SelectedOwnerIntakeAuthorizationBinding.fromStored(authorizationBytes);
    } catch (RuntimeException malformed) {
      throw invalid("Original authorization is malformed or noncanonical");
    }
    if (!authorization.digest().equals(wire.getIntakeAuthorizationDigest())) {
      throw invalid("Original authorization digest differs");
    }
    Request request =
        new Request(
            wire.getSchemaVersion(),
            wire.getTargetNamespace(),
            UUID.fromString(wire.getTransportRequestId()),
            authorization);
    if (!Arrays.equals(authorizationBytes, request.authorizationBindingBytes())
        || !toRequest(request).equals(wire)) {
      throw invalid("Settlement request is noncanonical");
    }
    return request;
  }

  public static SelectedOwnerIntakeSettlementResponse toResponse(Result result) {
    if (result == null) throw invalid("Complete settlement result is required");
    byte[] receipt = result.receipt().canonicalBytes();
    if (receipt.length == 0
        || receipt.length
            > AccountSelectedOwnerIntakeSettlementReceipt.maxBytes(
                result.request().authorizationBinding().owner())) {
      throw invalid("Complete settlement receipt exceeds its owner bound");
    }
    var response =
        SelectedOwnerIntakeSettlementResponse.newBuilder()
            .setRequest(toRequest(result.request()))
            .setSettlementReceipt(ByteString.copyFrom(receipt))
            .setSettlementReceiptDigest(result.receipt().digest())
            .build();
    requireWithinBudget(response.getSerializedSize(), MAX_RESPONSE_BYTES, "response");
    return response;
  }

  public static Result fromResponse(
      Request request, SelectedOwnerIntakeSettlementResponse response) {
    if (request == null) throw invalid("Original settlement request is required");
    requireKnownAndWithinBudget(response, MAX_RESPONSE_BYTES, "response");
    if (!response.hasRequest()) throw invalid("Exact settlement request echo is required");
    requireKnownAndWithinBudget(response.getRequest(), MAX_REQUEST_BYTES, "echoed request");
    if (!toRequest(request).equals(response.getRequest())) {
      throw invalid("Account changed the complete settlement request");
    }
    if (response.getSettlementReceipt().size()
        > AccountSelectedOwnerIntakeSettlementReceipt.maxBytes(
            request.authorizationBinding().owner())) {
      throw invalid("Complete settlement receipt exceeds its owner bound");
    }
    byte[] receiptBytes = response.getSettlementReceipt().toByteArray();
    AccountSelectedOwnerIntakeSettlementReceipt receipt;
    try {
      receipt = AccountSelectedOwnerIntakeSettlementReceipt.fromStored(receiptBytes);
    } catch (RuntimeException malformed) {
      throw invalid("Complete settlement receipt is malformed or noncanonical");
    }
    if (!Arrays.equals(receiptBytes, receipt.canonicalBytes())
        || !receipt.digest().equals(response.getSettlementReceiptDigest())
        || !Arrays.equals(
            request.authorizationBindingBytes(), receipt.authorizationBinding().canonicalBytes())
        || !request.authorizationBindingDigest().equals(receipt.authorizationBinding().digest())) {
      throw invalid("Settlement receipt digest or original authorization differs");
    }
    return new Result(request, receipt);
  }

  private static void requireKnownAndWithinBudget(Message wire, int maxBytes, String label) {
    if (wire == null || wire.getSerializedSize() > maxBytes) {
      throw invalid("Missing or oversized selected-owner settlement " + label);
    }
    if (!wire.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown selected-owner settlement " + label + " fields");
    }
  }

  private static void requireWithinBudget(int size, int maxBytes, String label) {
    if (size > maxBytes) {
      throw invalid("Selected-owner settlement " + label + " exceeds its wire budget");
    }
  }

  private static IllegalArgumentException invalid(String description) {
    return new IllegalArgumentException(description);
  }
}
