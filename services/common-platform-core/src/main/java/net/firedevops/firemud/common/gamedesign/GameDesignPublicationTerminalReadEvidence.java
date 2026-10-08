package net.firedevops.firemud.common.gamedesign;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;

/**
 * Closed immutable read correlation. Integrity alone grants no settlement or workload authority.
 */
public final class GameDesignPublicationTerminalReadEvidence {
  private GameDesignPublicationTerminalReadEvidence() {}

  public enum Status {
    UNKNOWN,
    PUBLISHED,
    NO_PUBLICATION
  }

  public record ReadRequest(
      int schemaVersion, String targetNamespace, UUID readRequestId, byte[] operationBytes) {
    public static final int SCHEMA_VERSION = 1;
    public static final String SCHEMA = "game-design-publication-terminal-read-request/v1";

    public ReadRequest {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || readRequestId == null
          || readRequestId.equals(new UUID(0, 0))) {
        throw new IllegalArgumentException(
            "Closed schema-1 namespace and non-nil read UUID required");
      }
      operationBytes =
          GameDesignPublicationOperationBinding.fromStored(operationBytes).canonicalBytes();
      var operation = GameDesignPublicationOperationBinding.fromStored(operationBytes);
      if (!targetNamespace.equals(operation.world().request().targetNamespace())) {
        throw new IllegalArgumentException("Read namespace differs from original World operation");
      }
      if (readRequestId.equals(operation.account().operationId())
          || readRequestId.equals(operation.account().fenceId())
          || readRequestId.equals(operation.world().request().intakeRequestId())
          || readRequestId.equals(operation.world().request().publicationFence())
          || readRequestId.toString().equals(operation.world().request().publicationRequestId())
          || readRequestId.equals(
              operation.account().input().selection().intent().selectedCommitRequestId())
          || readRequestId.equals(
              operation.account().input().selection().intent().selectedCommitId())) {
        throw new IllegalArgumentException(
            "Terminal read UUID must be distinct from original operation identity");
      }
    }

    public static ReadRequest create(String namespace, byte[] operationBytes) {
      return new ReadRequest(SCHEMA_VERSION, namespace, UUID.randomUUID(), operationBytes);
    }

    @Override
    public byte[] operationBytes() {
      return operationBytes.clone();
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, SCHEMA);
      DraftAuthorizationFenceBinding.frame(out, Integer.toString(schemaVersion));
      DraftAuthorizationFenceBinding.frame(out, targetNamespace);
      DraftAuthorizationFenceBinding.frame(out, readRequestId.toString());
      DraftAuthorizationFenceBinding.frame(out, operationBytes);
      return out.toByteArray();
    }

    public static ReadRequest fromStored(byte[] bytes) {
      var r = new DraftAuthorizationFenceBinding.FrameReader(bytes);
      r.expect(SCHEMA);
      r.expect("1");
      var result = new ReadRequest(1, r.text(), UUID.fromString(r.text()), r.bytes());
      r.requireEnd();
      if (!Arrays.equals(bytes, result.canonicalBytes()))
        throw new IllegalArgumentException("Noncanonical terminal read request");
      return result;
    }
  }

  public record ReadResult(
      ReadRequest request,
      Status status,
      Optional<GameDesignPublicationTerminalEvidence> terminalEvidence) {
    public static final String SCHEMA = "game-design-publication-terminal-read-response/v1";

    public ReadResult {
      request = ReadRequest.fromStored(Objects.requireNonNull(request).canonicalBytes());
      Objects.requireNonNull(status);
      Objects.requireNonNull(terminalEvidence);
      if ((status == Status.UNKNOWN) != terminalEvidence.isEmpty()) {
        throw new IllegalArgumentException("UNKNOWN alone omits terminal evidence");
      }
      if (terminalEvidence.isPresent()) {
        var terminal =
            GameDesignPublicationTerminalEvidence.fromStored(
                terminalEvidence.orElseThrow().canonicalBytes());
        if (!terminal.outcome().name().equals(status.name())
            || !Arrays.equals(request.operationBytes(), terminal.operationBytes())) {
          throw new IllegalArgumentException(
              "Terminal result differs from exact original read operation or outcome");
        }
        terminalEvidence = Optional.of(terminal);
      }
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, SCHEMA);
      DraftAuthorizationFenceBinding.frame(out, request.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, status.name());
      terminalEvidence.ifPresent(
          t -> DraftAuthorizationFenceBinding.frame(out, t.canonicalBytes()));
      return out.toByteArray();
    }

    public static ReadResult fromStored(byte[] bytes) {
      var r = new DraftAuthorizationFenceBinding.FrameReader(bytes);
      r.expect(SCHEMA);
      var request = ReadRequest.fromStored(r.bytes());
      var status = Status.valueOf(r.text());
      var terminal =
          status == Status.UNKNOWN
              ? Optional.<GameDesignPublicationTerminalEvidence>empty()
              : Optional.of(GameDesignPublicationTerminalEvidence.fromStored(r.bytes()));
      r.requireEnd();
      var result = new ReadResult(request, status, terminal);
      if (!Arrays.equals(bytes, result.canonicalBytes()))
        throw new IllegalArgumentException("Noncanonical terminal read response");
      return result;
    }
  }
}
