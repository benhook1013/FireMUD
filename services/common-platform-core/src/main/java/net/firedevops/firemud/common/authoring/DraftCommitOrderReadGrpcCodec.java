package net.firedevops.firemud.common.authoring;

import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.HeldOriginalCommitOrderStatus;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderRequest;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;

/** Closed wire mapping and exact request-echo validation for the Account held-order read. */
public final class DraftCommitOrderReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private DraftCommitOrderReadGrpcCodec() {}

  public static ReadHeldOriginalCommitOrderRequest toRequest(
      DraftCommitOrderReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadHeldOriginalCommitOrderRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalAccountBinding(
            com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()))
        .build();
  }

  /** Decode only after the Account server has authenticated its exact same-namespace World peer. */
  public static DraftCommitOrderReadEvidence.Request fromRequest(
      ReadHeldOriginalCommitOrderRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadHeldOriginalCommitOrderRequest");
    try {
      return new DraftCommitOrderReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalUuid(request.getReadRequestId()),
          request.getOriginalAccountBinding().toByteArray());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Account COMMIT_ORDER read request is invalid", invalid);
    }
  }

  /** Account may emit HELD only after its owner transaction proves the retained exact state. */
  public static ReadHeldOriginalCommitOrderResponse toHeldResponse(
      DraftCommitOrderReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadHeldOriginalCommitOrderResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalAccountBinding(
            com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()))
        .setStatus(HeldOriginalCommitOrderStatus.HELD_ORIGINAL_COMMIT_ORDER_STATUS_HELD)
        .build();
  }

  /** Validate a positive response; all non-held states are represented by non-OK RPC failures. */
  public static DraftCommitOrderReadEvidence fromResponse(
      DraftCommitOrderReadEvidence.Request request, ReadHeldOriginalCommitOrderResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadHeldOriginalCommitOrderResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())) {
      throw new IllegalArgumentException("Account response changed the exact COMMIT_ORDER read");
    }
    if (response.getStatus()
        != HeldOriginalCommitOrderStatus.HELD_ORIGINAL_COMMIT_ORDER_STATUS_HELD) {
      throw new IllegalArgumentException("Account did not confirm held original COMMIT_ORDER");
    }
    return new DraftCommitOrderReadEvidence(request);
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null) throw new IllegalArgumentException("Canonical read request UUID required");
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil read request UUID required");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Canonical non-nil read request UUID required", invalid);
    }
  }
}
