package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalResponse;

/** Closed version-one transport; structural validation never authenticates an owner. */
public final class GameDesignPublicationTerminalReadGrpcCodec {
  private GameDesignPublicationTerminalReadGrpcCodec() {}

  public static ReadPublicationTerminalRequest toRequest(
      GameDesignPublicationTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadPublicationTerminalRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
        .build();
  }

  /** Receiver must authenticate its exact same-namespace Account or World peer before decoding. */
  public static GameDesignPublicationTerminalReadEvidence.Request fromRequest(
      ReadPublicationTerminalRequest request) {
    Objects.requireNonNull(request, "request");
    noUnknownFields(request);
    return new GameDesignPublicationTerminalReadEvidence.Request(
        request.getSchemaVersion(), request.getTargetNamespace(),
        canonicalUuid(request.getReadRequestId()), request.getOriginalOperation().toByteArray());
  }

  /** Emit only an exact complete original terminal returned by the actual owner snapshot read. */
  public static ReadPublicationTerminalResponse toResponse(
      GameDesignPublicationTerminalReadEvidence.Request request,
      GameDesignPublicationTerminalEvidence terminal) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(terminal, "terminal");
    requireExactTerminal(request, terminal.canonicalBytes());
    return ReadPublicationTerminalResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
        .setTerminalEvidence(ByteString.copyFrom(terminal.canonicalBytes()))
        .build();
  }

  static GameDesignPublicationTerminalReadEvidence fromResponse(
      GameDesignPublicationTerminalReadEvidence.Request request,
      ReadPublicationTerminalResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    noUnknownFields(response);
    if (response.getSchemaVersion() != request.schemaVersion()
        || !request.targetNamespace().equals(response.getTargetNamespace())
        || !request.readRequestId().toString().equals(response.getReadRequestId())
        || !Arrays.equals(
            request.originalOperation(), response.getOriginalOperation().toByteArray())) {
      throw new IllegalArgumentException("Game Design changed the exact publication terminal read");
    }
    byte[] originalTerminal = response.getTerminalEvidence().toByteArray();
    requireExactTerminal(request, originalTerminal);
    return new GameDesignPublicationTerminalReadEvidence(request, originalTerminal);
  }

  private static void requireExactTerminal(
      GameDesignPublicationTerminalReadEvidence.Request request, byte[] bytes) {
    final GameDesignPublicationTerminalEvidence terminal;
    try {
      terminal = GameDesignPublicationTerminalEvidence.fromStored(bytes);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Complete canonical original terminal evidence required", malformed);
    }
    if (!Arrays.equals(request.originalOperation(), terminal.operationBytes())
        || !Arrays.equals(bytes, terminal.canonicalBytes())) {
      throw new IllegalArgumentException("Terminal evidence differs from the original operation");
    }
  }

  private static void noUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Publication terminal read contains unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Canonical non-nil read UUID required");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Canonical non-nil read UUID required", malformed);
    }
  }
}
