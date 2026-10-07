package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublicationTerminalResponse;

/** Closed wire mapping; receiver authentication must precede decoding. */
public final class WorldPublicationTerminalReadGrpcCodec {
  private WorldPublicationTerminalReadGrpcCodec() {}

  public static ReadWorldPublicationTerminalRequest toRequest(Request request) {
    return ReadWorldPublicationTerminalRequest.newBuilder()
        .setCanonicalRequestBytes(
            ByteString.copyFrom(Objects.requireNonNull(request).canonicalBytes()))
        .build();
  }

  public static Request fromRequest(ReadWorldPublicationTerminalRequest request) {
    if (!Objects.requireNonNull(request).getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytes().isEmpty()) {
      throw new IllegalArgumentException("Closed complete terminal read request required");
    }
    return Request.fromStored(request.getCanonicalRequestBytes().toByteArray());
  }

  public static ReadWorldPublicationTerminalResponse toResponse(
      Request request, ReadResult result) {
    exact(request, result);
    return ReadWorldPublicationTerminalResponse.newBuilder()
        .setCanonicalResponseBytes(ByteString.copyFrom(result.canonicalBytes()))
        .build();
  }

  public static ReadResult fromResponse(
      Request request, ReadWorldPublicationTerminalResponse response) {
    if (!Objects.requireNonNull(response).getUnknownFields().asMap().isEmpty()
        || response.getCanonicalResponseBytes().isEmpty()) {
      throw new IllegalArgumentException("Closed complete terminal read response required");
    }
    var result = ReadResult.fromStored(response.getCanonicalResponseBytes().toByteArray());
    exact(request, result);
    return result;
  }

  private static void exact(Request request, ReadResult result) {
    if (!Arrays.equals(
        Objects.requireNonNull(request).canonicalBytes(),
        Objects.requireNonNull(result).request().canonicalBytes())) {
      throw new IllegalArgumentException(
          "Terminal read response changed the complete request echo");
    }
  }
}
