package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationResponse;

/** Closed World-owned publication completion transport and exact result validation. */
public final class WorldPublicationTerminalCompletionGrpcCodec {
  /** Shared per-message protobuf budget; oversized complete operations/results fail closed. */
  public static final int MAX_MESSAGE_BYTES = 32 * 1024 * 1024;

  // Reserve the small envelope fields and protobuf tags/lengths before canonical decoding.
  private static final int MESSAGE_ENVELOPE_OVERHEAD_RESERVE = 256;

  private WorldPublicationTerminalCompletionGrpcCodec() {}

  public static CompleteVersionPublicationRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var wire =
        CompleteVersionPublicationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .setGameDesignTerminalEvidence(
                ByteString.copyFrom(request.gameDesignTerminalEvidence()))
            .build();
    requireWireBudget(wire);
    return wire;
  }

  /** Receiver authenticates the exact Game Design peer before decoding this request. */
  public static Request fromRequest(CompleteVersionPublicationRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireBudget(request);
    noUnknownFields(request);
    try {
      return new Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          canonicalUuid(request.getRequestId()),
          request.getOriginalOperation().toByteArray(),
          request.getGameDesignTerminalEvidence().toByteArray());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "World publication completion request is invalid", malformed);
    }
  }

  /** Emits the retained World result only when it is the exact requested Game Design terminal. */
  public static CompleteVersionPublicationResponse toResponse(
      Request request, GameDesignPublicationTerminalEvidence terminal) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(terminal, "terminal");
    byte[] terminalBytes = terminal.canonicalBytes();
    if (!Arrays.equals(request.originalOperation(), terminal.operationBytes())
        || !Arrays.equals(request.gameDesignTerminalEvidence(), terminalBytes)) {
      throw new IllegalArgumentException(
          "World publication terminal differs from the exact requested operation or evidence");
    }
    // The full terminal appears once in the request echo and once as the stored result.
    if ((long) request.originalOperation().length + 2L * terminalBytes.length
        > MAX_MESSAGE_BYTES - MESSAGE_ENVELOPE_OVERHEAD_RESERVE) {
      throw new IllegalArgumentException(
          "World publication completion response exceeds the 32 MiB wire budget");
    }
    var response =
        CompleteVersionPublicationResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .setGameDesignTerminalEvidence(
                ByteString.copyFrom(request.gameDesignTerminalEvidence()))
            .setStoredTerminalEvidence(ByteString.copyFrom(terminalBytes))
            .build();
    requireWireBudget(response);
    return response;
  }

  /** Verifies full request echo and returns the fully decoded retained terminal result. */
  public static GameDesignPublicationTerminalEvidence fromResponse(
      Request request, CompleteVersionPublicationResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireWireBudget(response);
    noUnknownFields(response);
    if (response.getSchemaVersion() != request.schemaVersion()
        || !request.targetNamespace().equals(response.getTargetNamespace())
        || !request.requestId().toString().equals(response.getRequestId())
        || !Arrays.equals(
            request.originalOperation(), response.getOriginalOperation().toByteArray())
        || !Arrays.equals(
            request.gameDesignTerminalEvidence(),
            response.getGameDesignTerminalEvidence().toByteArray())) {
      throw new IllegalArgumentException("World changed the exact publication completion request");
    }
    byte[] terminalBytes = response.getStoredTerminalEvidence().toByteArray();
    return requireExactTerminal(request, terminalBytes);
  }

  private static GameDesignPublicationTerminalEvidence requireExactTerminal(
      Request request, byte[] terminalBytes) {
    final GameDesignPublicationTerminalEvidence terminal;
    try {
      terminal = GameDesignPublicationTerminalEvidence.fromStored(terminalBytes);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Complete canonical publication terminal required", malformed);
    }
    if (!Arrays.equals(request.originalOperation(), terminal.operationBytes())
        || !Arrays.equals(request.gameDesignTerminalEvidence(), terminal.canonicalBytes())) {
      throw new IllegalArgumentException(
          "World publication terminal differs from the exact requested operation or evidence");
    }
    return terminal;
  }

  private static void requireWireBudget(Message message) {
    if (message.getSerializedSize() > MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException(
          "World publication completion exceeds the 32 MiB wire budget");
    }
  }

  private static void noUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("World publication completion has unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Canonical non-nil request UUID required");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Canonical non-nil request UUID required", malformed);
    }
  }

  /** Request correlation is fresh per transport call and is not the mutation identity. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      byte[] originalOperation,
      byte[] gameDesignTerminalEvidence) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World publication completion schema");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical World workload namespace required");
      }
      if (requestId == null || requestId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Non-nil canonical request identity required");
      }
      if (originalOperation == null || originalOperation.length == 0) {
        throw new IllegalArgumentException("Complete original publication operation required");
      }
      if (originalOperation.length > GameDesignPublicationOperationBinding.MAX_OPERATION_BYTES) {
        throw new IllegalArgumentException("Original publication operation exceeds 4 MiB");
      }
      if (gameDesignTerminalEvidence == null || gameDesignTerminalEvidence.length == 0) {
        throw new IllegalArgumentException("Complete Game Design terminal evidence required");
      }
      if ((long) originalOperation.length + gameDesignTerminalEvidence.length
          > MAX_MESSAGE_BYTES - MESSAGE_ENVELOPE_OVERHEAD_RESERVE) {
        throw new IllegalArgumentException(
            "World publication completion exceeds the 32 MiB wire budget");
      }
      originalOperation = originalOperation.clone();
      gameDesignTerminalEvidence = gameDesignTerminalEvidence.clone();
      GameDesignPublicationTerminalEvidence terminal;
      try {
        // Historical decoding must not widen this complete-v2 completion transport.
        new DraftAuthorizationFenceBinding.FrameReader(originalOperation)
            .expect(GameDesignPublicationOperationBinding.SCHEMA);
        terminal = GameDesignPublicationTerminalEvidence.fromStored(gameDesignTerminalEvidence);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException(
            "Complete canonical publication operation and terminal required", malformed);
      }
      if (!Arrays.equals(originalOperation, terminal.operationBytes())
          || !Arrays.equals(gameDesignTerminalEvidence, terminal.canonicalBytes())) {
        throw new IllegalArgumentException(
            "Game Design terminal differs from the complete original operation");
      }
      if (!targetNamespace.equals(terminal.worldEvidence().request().targetNamespace())) {
        throw new IllegalArgumentException(
            "Original publication operation differs from the World workload namespace");
      }
    }

    public static Request create(
        String targetNamespace, byte[] originalOperation, byte[] gameDesignTerminalEvidence) {
      return new Request(
          SCHEMA_VERSION,
          targetNamespace,
          UUID.randomUUID(),
          originalOperation,
          gameDesignTerminalEvidence);
    }

    @Override
    public byte[] originalOperation() {
      return originalOperation.clone();
    }

    @Override
    public byte[] gameDesignTerminalEvidence() {
      return gameDesignTerminalEvidence.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && requestId.equals(that.requestId)
          && Arrays.equals(originalOperation, that.originalOperation)
          && Arrays.equals(gameDesignTerminalEvidence, that.gameDesignTerminalEvidence);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(schemaVersion, targetNamespace, requestId);
      result = 31 * result + Arrays.hashCode(originalOperation);
      return 31 * result + Arrays.hashCode(gameDesignTerminalEvidence);
    }
  }
}
