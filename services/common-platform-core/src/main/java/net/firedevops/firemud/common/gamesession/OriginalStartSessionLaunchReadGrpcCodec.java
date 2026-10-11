package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchResponse;

/** Closed bounded wire mapping for the Account-only exact original StartSession launch read. */
public final class OriginalStartSessionLaunchReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES =
      OriginalStartSessionLaunchReadEvidence.MAX_REQUEST_BYTES;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      OriginalStartSessionLaunchReadEvidence.MAX_RESULT_BYTES;

  private OriginalStartSessionLaunchReadGrpcCodec() {}

  public static ReadOriginalStartSessionLaunchRequest toRequest(
      OriginalStartSessionLaunchReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    byte[] exactCanonicalBytes = request.canonicalBytes();
    requireSize(exactCanonicalBytes.length, MAX_REQUEST_WIRE_BYTES, "request");
    try {
      ReadOriginalStartSessionLaunchRequest wire =
          ReadOriginalStartSessionLaunchRequest.parseFrom(exactCanonicalBytes);
      requireNoUnknownFields(wire, "original StartSession launch read request");
      if (!java.util.Arrays.equals(exactCanonicalBytes, wire.toByteArray())) {
        throw new IllegalArgumentException("Original StartSession launch request is not canonical");
      }
      return wire;
    } catch (java.io.IOException malformed) {
      throw new IllegalArgumentException(
          "Original StartSession launch request is malformed", malformed);
    }
  }

  /** Decodes a complete closed request; this operation does not authenticate its caller. */
  public static OriginalStartSessionLaunchReadEvidence.Request fromRequest(
      ReadOriginalStartSessionLaunchRequest request) {
    Objects.requireNonNull(request, "request");
    requireSize(request.getSerializedSize(), MAX_REQUEST_WIRE_BYTES, "request");
    requireNoUnknownFields(request, "original StartSession launch read request");
    return OriginalStartSessionLaunchReadEvidence.Request.decode(request.toByteArray());
  }

  public static ReadOriginalStartSessionLaunchResponse toResponse(
      OriginalStartSessionLaunchReadEvidence.Result result) {
    Objects.requireNonNull(result, "result");
    byte[] exactCanonicalBytes = result.canonicalBytes();
    requireSize(exactCanonicalBytes.length, MAX_RESPONSE_WIRE_BYTES, "response");
    try {
      ReadOriginalStartSessionLaunchResponse wire =
          ReadOriginalStartSessionLaunchResponse.parseFrom(exactCanonicalBytes);
      requireNoUnknownFields(wire, "original StartSession launch read response");
      if (!java.util.Arrays.equals(exactCanonicalBytes, wire.toByteArray())) {
        throw new IllegalArgumentException(
            "Original StartSession launch response is not canonical");
      }
      return wire;
    } catch (java.io.IOException malformed) {
      throw new IllegalArgumentException(
          "Original StartSession launch response is malformed", malformed);
    }
  }

  /**
   * Validates the complete echoed request, launch projection, and actual retained original tuple.
   */
  public static OriginalStartSessionLaunchReadEvidence.Result fromResponse(
      OriginalStartSessionLaunchReadEvidence.Request expectedRequest,
      ReadOriginalStartSessionLaunchResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireSize(response.getSerializedSize(), MAX_RESPONSE_WIRE_BYTES, "response");
    requireNoUnknownFields(response, "original StartSession launch read response");
    OriginalStartSessionLaunchReadEvidence.Result result =
        OriginalStartSessionLaunchReadEvidence.Result.decode(response.toByteArray());
    if (!expectedRequest.equals(result.request()) || !toResponse(result).equals(response)) {
      throw new IllegalArgumentException(
          "Game Session original StartSession launch read changed the exact request");
    }
    return result;
  }

  private static void requireSize(int size, int maximum, String label) {
    if (size <= 0 || size > maximum) {
      throw new IllegalArgumentException(
          "Original StartSession launch read " + label + " is empty or exceeds its byte limit");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (var field : message.getAllFields().entrySet()) {
      Descriptors.FieldDescriptor descriptor = field.getKey();
      if (descriptor.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
      if (descriptor.isRepeated()) {
        for (Object nested : (List<?>) field.getValue()) {
          requireNoUnknownFields((Message) nested, label + "." + descriptor.getName());
        }
      } else {
        requireNoUnknownFields((Message) field.getValue(), label + "." + descriptor.getName());
      }
    }
  }
}
