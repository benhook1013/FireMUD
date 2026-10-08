package net.firedevops.firemud.common.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * World-owned readback of its terminal phase and the exact embedded Game Design terminal bytes.
 * Decoding alone does not authenticate World; only the strict World mTLS client establishes that
 * producer identity. The embedded terminal remains Game Design-produced evidence.
 */
public final class WorldPublicationTerminalReadEvidence {
  private final Request request;
  private final WorldOutcome worldOutcome;
  private final GameDesignPublicationTerminalEvidence terminal;

  private WorldPublicationTerminalReadEvidence(
      Request request, WorldOutcome worldOutcome, byte[] worldTerminalEvidence) {
    this.request = Objects.requireNonNull(request, "request");
    this.worldOutcome = Objects.requireNonNull(worldOutcome, "worldOutcome");
    this.terminal = GameDesignPublicationTerminalEvidence.fromStored(worldTerminalEvidence);
    if (!Arrays.equals(request.originalOperation(), terminal.operationBytes())
        || !Arrays.equals(request.expectedGameDesignTerminalEvidence(), terminal.canonicalBytes())
        || worldOutcome != worldOutcome(terminal.outcome())) {
      throw new IllegalArgumentException(
          "World terminal read differs from the exact operation, terminal, or owner phase");
    }
  }

  public static WorldPublicationTerminalReadEvidence fromOwnerRead(
      Request request, WorldOutcome worldOutcome, byte[] worldTerminalEvidence) {
    return new WorldPublicationTerminalReadEvidence(request, worldOutcome, worldTerminalEvidence);
  }

  public Request request() {
    return request;
  }

  /** World owner phase, not the identity of the producer of {@link #terminalEvidence()}. */
  public WorldOutcome worldOutcome() {
    return worldOutcome;
  }

  /** Exact original terminal payload, whose producer is Game Design. */
  public GameDesignPublicationTerminalEvidence terminalEvidence() {
    return terminal;
  }

  public byte[] worldTerminalEvidence() {
    return terminal.canonicalBytes();
  }

  private static WorldOutcome worldOutcome(GameDesignPublicationTerminalEvidence.Outcome outcome) {
    return outcome == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        ? WorldOutcome.PUBLISHED
        : WorldOutcome.ABORTED;
  }

  public enum WorldOutcome {
    PUBLISHED,
    ABORTED
  }

  /**
   * Exact owner-read lookup tuple. The read UUID is only transport correlation; settlement remains
   * identified by the immutable Account order nested in the original operation.
   */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] originalOperation,
      byte[] expectedGameDesignTerminalEvidence) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World publication terminal read schema");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical World workload namespace required");
      }
      if (readRequestId == null || readRequestId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Non-nil canonical read request identity required");
      }
      if (originalOperation == null || originalOperation.length == 0) {
        throw new IllegalArgumentException("Complete original publication operation required");
      }
      if (expectedGameDesignTerminalEvidence == null
          || expectedGameDesignTerminalEvidence.length == 0) {
        throw new IllegalArgumentException("Exact expected Game Design terminal evidence required");
      }
      originalOperation = originalOperation.clone();
      expectedGameDesignTerminalEvidence = expectedGameDesignTerminalEvidence.clone();
      GameDesignPublicationOperationBinding operation;
      GameDesignPublicationTerminalEvidence terminal;
      try {
        operation = GameDesignPublicationOperationBinding.fromStored(originalOperation);
        terminal =
            GameDesignPublicationTerminalEvidence.fromStored(expectedGameDesignTerminalEvidence);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException(
            "Canonical complete publication operation and terminal evidence required", malformed);
      }
      if (!Arrays.equals(originalOperation, operation.canonicalBytes())
          || !Arrays.equals(expectedGameDesignTerminalEvidence, terminal.canonicalBytes())
          || !Arrays.equals(originalOperation, terminal.operationBytes())) {
        throw new IllegalArgumentException(
            "Expected terminal evidence differs from the exact original operation");
      }
      if (!targetNamespace.equals(operation.world().request().targetNamespace())) {
        throw new IllegalArgumentException(
            "Original publication operation differs from the World workload namespace");
      }
    }

    public static Request create(
        String targetNamespace,
        byte[] originalOperation,
        byte[] expectedGameDesignTerminalEvidence) {
      return new Request(
          SCHEMA_VERSION,
          targetNamespace,
          UUID.randomUUID(),
          originalOperation,
          expectedGameDesignTerminalEvidence);
    }

    @Override
    public byte[] originalOperation() {
      return originalOperation.clone();
    }

    @Override
    public byte[] expectedGameDesignTerminalEvidence() {
      return expectedGameDesignTerminalEvidence.clone();
    }

    public WorldOutcome expectedWorldOutcome() {
      return worldOutcome(
          GameDesignPublicationTerminalEvidence.fromStored(expectedGameDesignTerminalEvidence)
              .outcome());
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && readRequestId.equals(that.readRequestId)
          && Arrays.equals(originalOperation, that.originalOperation)
          && Arrays.equals(
              expectedGameDesignTerminalEvidence, that.expectedGameDesignTerminalEvidence);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(schemaVersion, targetNamespace, readRequestId);
      result = 31 * result + Arrays.hashCode(originalOperation);
      return 31 * result + Arrays.hashCode(expectedGameDesignTerminalEvidence);
    }
  }
}
