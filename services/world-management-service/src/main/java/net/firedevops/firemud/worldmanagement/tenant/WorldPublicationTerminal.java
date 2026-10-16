package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed, immutable World handoff to the exact Game Design publication terminal result. */
public final class WorldPublicationTerminal {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private WorldPublicationTerminal() {}

  /**
   * Reconstructs the only accepted terminal carrier; decoding does not authenticate Game Design.
   */
  public static Request request(byte[] terminalBytes) {
    return new Request(GameDesignPublicationTerminalEvidence.fromStored(terminalBytes));
  }

  public record Request(GameDesignPublicationTerminalEvidence evidence) {
    public Request {
      Objects.requireNonNull(evidence, "evidence");
      // The final evidence type already validates and defensively retains its canonical bytes.
    }

    public WorldPublishedStartLocationEvidence worldEvidence() {
      return evidence.worldEvidence();
    }

    public byte[] terminalBytes() {
      return evidence.canonicalBytes();
    }

    public byte[] operationBytes() {
      return evidence.operationBytes();
    }

    public byte[] worldEvidenceBytes() {
      return evidence.worldEvidence().canonicalBytes();
    }

    public String worldRequestJson() {
      try {
        JsonNode root = JSON.readTree(worldEvidenceBytes());
        JsonNode request = root == null ? null : root.get("request");
        if (request == null || !request.isObject()) {
          throw new IllegalArgumentException("Terminal evidence has no exact World request");
        }
        byte[] canonical =
            Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(request));
        return new String(canonical, java.nio.charset.StandardCharsets.UTF_8);
      } catch (IOException | JacksonException invalid) {
        throw new IllegalArgumentException(
            "Terminal evidence has an invalid World request", invalid);
      }
    }

    public byte[] originalAccountBindingBytes() {
      return worldEvidence().originalAccountBindingBytes();
    }

    public byte[] selectorReceiptBytes() {
      return worldEvidence().selectorReceiptBytes();
    }

    public byte[] appliedResultBytes() {
      return worldEvidence().appliedResultBytes();
    }

    public byte[] releaseContentBytes() {
      return evidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
          ? evidence.releaseContent().canonicalBytes()
          : new byte[0];
    }

    public String ownerPhase() {
      return evidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
          ? "PUBLISHED"
          : "ABORTED";
    }

    public String releaseBundleRef() {
      return evidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
          ? evidence.publishedReleaseBundleRef()
          : null;
    }

    public String releaseBundleDigest() {
      return evidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
          ? evidence.publishedReleaseBundleDigest()
          : null;
    }

    public Long publicationVersionStateEpoch() {
      return evidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
          ? evidence.publicationVersionStateEpoch()
          : null;
    }

    public static Request fromStored(byte[] bytes) {
      return request(bytes);
    }

    public boolean exactBytes(byte[] bytes) {
      return Arrays.equals(terminalBytes(), bytes);
    }
  }
}
