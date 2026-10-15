package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence.WorldOutcome;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalReadOutcome;

/** Closed World-owned transport mapping and exact echo/outcome validation. */
public final class WorldPublicationTerminalReadGrpcCodec {
  static final int MESSAGE_ENVELOPE_OVERHEAD_RESERVE = 256;

  private WorldPublicationTerminalReadGrpcCodec() {}

  public static ReadPublicationTerminalRequest toRequest(
      WorldPublicationTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    var wire =
        ReadPublicationTerminalRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .setExpectedGameDesignTerminalEvidence(
                ByteString.copyFrom(request.expectedGameDesignTerminalEvidence()))
            .build();
    requireWireBudget(wire);
    return wire;
  }

  /** Caller authenticates the exact Account peer before invoking this decoder. */
  public static WorldPublicationTerminalReadEvidence.Request fromRequest(
      ReadPublicationTerminalRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireBudget(request);
    noUnknownFields(request);
    try {
      return new WorldPublicationTerminalReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          canonicalUuid(request.getReadRequestId()),
          request.getOriginalOperation().toByteArray(),
          request.getExpectedGameDesignTerminalEvidence().toByteArray());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "World publication terminal read request is invalid", malformed);
    }
  }

  /** Emits the exact original terminal only after the World owner read confirms its phase. */
  public static ReadPublicationTerminalResponse toResponse(
      WorldPublicationTerminalReadEvidence.Request request,
      GameDesignPublicationTerminalEvidence terminal) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(terminal, "terminal");
    byte[] terminalBytes = terminal.canonicalBytes();
    byte[] originalOperation = request.originalOperation();
    byte[] expectedTerminal = request.expectedGameDesignTerminalEvidence();
    requireResponsePayloadBudget(originalOperation.length, terminalBytes.length);
    if (!Arrays.equals(originalOperation, terminal.operationBytes())
        || !Arrays.equals(expectedTerminal, terminalBytes)) {
      throw new IllegalArgumentException(
          "World terminal read differs from the exact operation, terminal, or owner phase");
    }
    WorldOutcome outcome =
        terminal.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
            ? WorldOutcome.PUBLISHED
            : WorldOutcome.ABORTED;
    var response =
        ReadPublicationTerminalResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalOperation(ByteString.copyFrom(originalOperation))
            .setExpectedGameDesignTerminalEvidence(ByteString.copyFrom(expectedTerminal))
            .setWorldOutcome(toWireOutcome(outcome))
            .setWorldTerminalEvidence(ByteString.copyFrom(terminalBytes))
            .build();
    requireWireBudget(response);
    return response;
  }

  /** Verifies complete echo, canonical terminal bytes, and World owner phase mapping. */
  public static WorldPublicationTerminalReadEvidence fromResponse(
      WorldPublicationTerminalReadEvidence.Request request,
      ReadPublicationTerminalResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireWireBudget(response);
    noUnknownFields(response);
    if (response.getSchemaVersion() != request.schemaVersion()
        || !request.targetNamespace().equals(response.getTargetNamespace())
        || !request.readRequestId().toString().equals(response.getReadRequestId())
        || !Arrays.equals(
            request.originalOperation(), response.getOriginalOperation().toByteArray())
        || !Arrays.equals(
            request.expectedGameDesignTerminalEvidence(),
            response.getExpectedGameDesignTerminalEvidence().toByteArray())) {
      throw new IllegalArgumentException(
          "World changed the exact publication terminal read request");
    }
    WorldOutcome outcome = fromWireOutcome(response.getWorldOutcome());
    return WorldPublicationTerminalReadEvidence.fromOwnerRead(
        request, outcome, response.getWorldTerminalEvidence().toByteArray());
  }

  private static WorldPublicationTerminalReadOutcome toWireOutcome(WorldOutcome outcome) {
    return outcome == WorldOutcome.PUBLISHED
        ? WorldPublicationTerminalReadOutcome.WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_PUBLISHED
        : WorldPublicationTerminalReadOutcome.WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_ABORTED;
  }

  private static WorldOutcome fromWireOutcome(WorldPublicationTerminalReadOutcome outcome) {
    return switch (outcome) {
      case WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_PUBLISHED -> WorldOutcome.PUBLISHED;
      case WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_ABORTED -> WorldOutcome.ABORTED;
      case WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("World owner terminal outcome is required");
    };
  }

  private static void noUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("World publication terminal read has unsupported fields");
    }
  }

  private static void requireWireBudget(Message message) {
    if (message.getSerializedSize()
        > WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException(
          "World publication terminal read exceeds the 32 MiB wire budget");
    }
  }

  private static void requireResponsePayloadBudget(
      long originalOperationBytes, long terminalBytes) {
    if (originalOperationBytes + 2L * terminalBytes
        > WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES
            - MESSAGE_ENVELOPE_OVERHEAD_RESERVE) {
      throw new IllegalArgumentException(
          "World publication terminal read response exceeds the 32 MiB wire budget");
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
