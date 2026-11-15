package net.firedevops.firemud.common.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Complete original terminal evidence returned by the strict authenticated Game Design client.
 * Structural decoding alone is not owner authentication. Consumers still own settlement,
 * applicability and admission decisions; this value does not perform those decisions.
 */
public final class GameDesignPublicationTerminalReadEvidence {
  private final Request request;
  private final GameDesignPublicationTerminalEvidence terminal;

  GameDesignPublicationTerminalReadEvidence(Request request, byte[] terminalBytes) {
    this.request = Objects.requireNonNull(request, "request");
    this.terminal = GameDesignPublicationTerminalEvidence.fromStored(terminalBytes);
    if (!Arrays.equals(request.originalOperation(), terminal.operationBytes())
        || !Arrays.equals(terminalBytes, terminal.canonicalBytes())) {
      throw new IllegalArgumentException(
          "Terminal evidence differs from the exact original operation");
    }
  }

  public Request request() {
    return request;
  }

  public GameDesignPublicationTerminalEvidence terminalEvidence() {
    return terminal;
  }

  /** Complete canonical original operation and independent read correlation. */
  public record Request(
      int schemaVersion, String targetNamespace, UUID readRequestId, byte[] originalOperation) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported publication terminal read schema");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Game Design workload namespace required");
      }
      if (readRequestId == null || readRequestId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Non-nil read request identity required");
      }
      if (originalOperation == null || originalOperation.length == 0) {
        throw new IllegalArgumentException("Complete original publication operation required");
      }
      originalOperation = originalOperation.clone();
      GameDesignPublicationOperationBinding operation;
      try {
        operation = GameDesignPublicationOperationBinding.fromStored(originalOperation);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException(
            "Complete canonical original operation required", malformed);
      }
      if (!Arrays.equals(originalOperation, operation.canonicalBytes())) {
        throw new IllegalArgumentException("Exact canonical original operation required");
      }
    }

    public static Request create(
        String targetNamespace, GameDesignPublicationOperationBinding operation) {
      Objects.requireNonNull(operation, "operation");
      return new Request(
          SCHEMA_VERSION, targetNamespace, UUID.randomUUID(), operation.canonicalBytes());
    }

    @Override
    public byte[] originalOperation() {
      return originalOperation.clone();
    }

    public GameDesignPublicationOperationBinding operation() {
      return GameDesignPublicationOperationBinding.fromStored(originalOperation);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && readRequestId.equals(that.readRequestId)
          && Arrays.equals(originalOperation, that.originalOperation);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(schemaVersion, targetNamespace, readRequestId)
          + Arrays.hashCode(originalOperation);
    }
  }
}
