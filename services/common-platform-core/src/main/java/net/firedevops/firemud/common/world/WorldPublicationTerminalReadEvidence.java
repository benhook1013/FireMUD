package net.firedevops.firemud.common.world;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;

/** Exact World owner-read correlation. Copied GD bytes alone prove no World commit or authority. */
public final class WorldPublicationTerminalReadEvidence {
  private WorldPublicationTerminalReadEvidence() {}

  public enum Status {
    UNKNOWN,
    PUBLISHED,
    ABORTED
  }

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] expectedTerminalEvidenceBytes) {
    public static final int SCHEMA_VERSION = 1;
    public static final String SCHEMA = "world-publication-terminal-read-request/v1";

    public Request {
      var terminal =
          GameDesignPublicationTerminalEvidence.fromStored(expectedTerminalEvidenceBytes);
      // Reuse the canonical namespace, schema and distinct read-UUID validation of the operation.
      new GameDesignPublicationTerminalReadEvidence.ReadRequest(
          schemaVersion, targetNamespace, readRequestId, terminal.operationBytes());
      expectedTerminalEvidenceBytes = terminal.canonicalBytes();
    }

    public static Request create(String namespace, byte[] expectedTerminalEvidenceBytes) {
      return new Request(
          SCHEMA_VERSION, namespace, UUID.randomUUID(), expectedTerminalEvidenceBytes);
    }

    @Override
    public byte[] expectedTerminalEvidenceBytes() {
      return expectedTerminalEvidenceBytes.clone();
    }

    public GameDesignPublicationTerminalEvidence terminalEvidence() {
      return GameDesignPublicationTerminalEvidence.fromStored(expectedTerminalEvidenceBytes);
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, SCHEMA);
      DraftAuthorizationFenceBinding.frame(out, Integer.toString(schemaVersion));
      DraftAuthorizationFenceBinding.frame(out, targetNamespace);
      DraftAuthorizationFenceBinding.frame(out, readRequestId.toString());
      DraftAuthorizationFenceBinding.frame(out, expectedTerminalEvidenceBytes);
      return out.toByteArray();
    }

    public static Request fromStored(byte[] bytes) {
      var r = new DraftAuthorizationFenceBinding.FrameReader(bytes);
      r.expect(SCHEMA);
      r.expect("1");
      var result = new Request(1, r.text(), UUID.fromString(r.text()), r.bytes());
      r.requireEnd();
      if (!Arrays.equals(bytes, result.canonicalBytes()))
        throw new IllegalArgumentException("Noncanonical World terminal read request");
      return result;
    }
  }

  public record ReadResult(
      Request request,
      Status status,
      Optional<GameDesignPublicationTerminalEvidence> terminalEvidence) {
    public static final String SCHEMA = "world-publication-terminal-read-response/v1";

    public ReadResult {
      request = Request.fromStored(Objects.requireNonNull(request).canonicalBytes());
      Objects.requireNonNull(status);
      Objects.requireNonNull(terminalEvidence);
      if ((status == Status.UNKNOWN) != terminalEvidence.isEmpty()) {
        throw new IllegalArgumentException("UNKNOWN alone omits World terminal evidence");
      }
      if (terminalEvidence.isPresent()) {
        var terminal =
            GameDesignPublicationTerminalEvidence.fromStored(
                terminalEvidence.orElseThrow().canonicalBytes());
        var expectedStatus =
            terminal.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
                ? Status.PUBLISHED
                : Status.ABORTED;
        if (status != expectedStatus
            || !Arrays.equals(request.expectedTerminalEvidenceBytes(), terminal.canonicalBytes())) {
          throw new IllegalArgumentException(
              "World result differs from exact expected terminal bytes or outcome");
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
      var request = Request.fromStored(r.bytes());
      var status = Status.valueOf(r.text());
      var terminal =
          status == Status.UNKNOWN
              ? Optional.<GameDesignPublicationTerminalEvidence>empty()
              : Optional.of(GameDesignPublicationTerminalEvidence.fromStored(r.bytes()));
      r.requireEnd();
      var result = new ReadResult(request, status, terminal);
      if (!Arrays.equals(bytes, result.canonicalBytes()))
        throw new IllegalArgumentException("Noncanonical World terminal read response");
      return result;
    }
  }
}
