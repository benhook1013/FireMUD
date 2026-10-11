package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;

/** Closed byte-carrier mapping for the authenticated, read-only World lifecycle RPC. */
public final class WorldCanonicalInstanceLifecycleGrpcCodec {
  private WorldCanonicalInstanceLifecycleGrpcCodec() {}

  /** Encodes the complete closed request as one canonical byte value. */
  public static ReadWorldCanonicalInstanceLifecycleRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    return ReadWorldCanonicalInstanceLifecycleRequest.newBuilder()
        .setCanonicalRequestBytes(ByteString.copyFrom(request.canonicalBytes()))
        .build();
  }

  /** Decodes only after the World receiver authenticates its exact same-namespace caller. */
  public static Request fromRequest(ReadWorldCanonicalInstanceLifecycleRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadWorldCanonicalInstanceLifecycleRequest");
    if (request.getCanonicalRequestBytes().isEmpty()) {
      throw new IllegalArgumentException("World canonical lifecycle request bytes are required");
    }
    return Request.fromStored(request.getCanonicalRequestBytes().toByteArray());
  }

  /** Encodes a complete response only when it contains the exact immutable caller request. */
  public static ReadWorldCanonicalInstanceLifecycleResponse toResponse(
      Request request, WorldCanonicalInstanceLifecycleEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException(
          "World lifecycle evidence differs from exact read request");
    }
    return ReadWorldCanonicalInstanceLifecycleResponse.newBuilder()
        .setCanonicalResponseBytes(ByteString.copyFrom(evidence.canonicalBytes()))
        .build();
  }

  /** Validates the complete closed response and its exact request echo. */
  public static WorldCanonicalInstanceLifecycleEvidence fromResponse(
      Request request, ReadWorldCanonicalInstanceLifecycleResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadWorldCanonicalInstanceLifecycleResponse");
    if (response.getCanonicalResponseBytes().isEmpty()) {
      throw new IllegalArgumentException("World canonical lifecycle response bytes are required");
    }
    WorldCanonicalInstanceLifecycleEvidence evidence =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(
            response.getCanonicalResponseBytes().toByteArray());
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException("World lifecycle response changed exact read request");
    }
    return evidence;
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }
}
