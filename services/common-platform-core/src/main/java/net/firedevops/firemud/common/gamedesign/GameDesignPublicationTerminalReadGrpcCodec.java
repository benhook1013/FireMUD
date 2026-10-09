package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;

/** Closed wire mapping; receiver authentication must precede decoding. */
public final class GameDesignPublicationTerminalReadGrpcCodec {
  private GameDesignPublicationTerminalReadGrpcCodec() {}

  public static ReadGameDesignPublicationTerminalRequest toRequest(ReadRequest request) {
    return ReadGameDesignPublicationTerminalRequest.newBuilder()
        .setCanonicalRequestBytes(
            ByteString.copyFrom(Objects.requireNonNull(request).canonicalBytes()))
        .build();
  }

  public static ReadRequest fromRequest(ReadGameDesignPublicationTerminalRequest request) {
    if (!Objects.requireNonNull(request).getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytes().isEmpty()) {
      throw new IllegalArgumentException("Closed complete terminal read request required");
    }
    return ReadRequest.fromStored(request.getCanonicalRequestBytes().toByteArray());
  }

  public static ReadGameDesignPublicationTerminalResponse toResponse(
      ReadRequest request, ReadResult result) {
    exact(request, result);
    return ReadGameDesignPublicationTerminalResponse.newBuilder()
        .setCanonicalResponseBytes(ByteString.copyFrom(result.canonicalBytes()))
        .build();
  }

  public static ReadResult fromResponse(
      ReadRequest request, ReadGameDesignPublicationTerminalResponse response) {
    if (!Objects.requireNonNull(response).getUnknownFields().asMap().isEmpty()
        || response.getCanonicalResponseBytes().isEmpty()) {
      throw new IllegalArgumentException("Closed complete terminal read response required");
    }
    var result = ReadResult.fromStored(response.getCanonicalResponseBytes().toByteArray());
    exact(request, result);
    return result;
  }

  private static void exact(ReadRequest request, ReadResult result) {
    if (!Arrays.equals(
        Objects.requireNonNull(request).canonicalBytes(),
        Objects.requireNonNull(result).request().canonicalBytes())) {
      throw new IllegalArgumentException(
          "Terminal read response changed the complete request echo");
    }
  }
}
