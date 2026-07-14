package net.firedevops.firemud.cache;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Stores a bounded per-player transcript window for reconnect context restoration. */
public interface ScreenBufferService {
  void append(
      long tenantId, long gameInstanceId, long characterId, java.util.List<BufferedEntry> entries);

  /**
   * Replaces the complete reconnect transcript for one scope without exposing an empty interim
   * view.
   */
  void replace(
      long tenantId, long gameInstanceId, long characterId, java.util.List<BufferedEntry> entries);

  Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId);

  void clear(long tenantId, long gameInstanceId, long characterId);

  record BufferedEntry(
      String text,
      int lineCount,
      int byteSize,
      long appendedAtMs,
      String outputKind,
      String replayPolicy,
      String briefRenderPolicy,
      String payloadType,
      String payloadJson,
      long orderingToken) {
    public BufferedEntry {
      text = text == null ? "" : text;
    }

    public BufferedEntry(
        String text,
        int lineCount,
        int byteSize,
        long appendedAtMs,
        String outputKind,
        String replayPolicy,
        String briefRenderPolicy,
        String payloadType,
        String payloadJson) {
      this(
          text,
          lineCount,
          byteSize,
          appendedAtMs,
          outputKind,
          replayPolicy,
          briefRenderPolicy,
          payloadType,
          payloadJson,
          0L);
    }

    public BufferedEntry(String text, int lineCount, int byteSize, long appendedAtMs) {
      this(text, lineCount, byteSize, appendedAtMs, null, null, null, null, null, 0L);
    }

    public static BufferedEntry fromText(String text) {
      return fromStructuredOutput(text, null, null, null, null, null);
    }

    public static BufferedEntry fromStructuredOutput(
        String text,
        String outputKind,
        String replayPolicy,
        String briefRenderPolicy,
        String payloadType,
        String payloadJson) {
      String safeText = text == null ? "" : text;
      return new BufferedEntry(
          safeText,
          (int) safeText.lines().filter(line -> !line.isBlank()).count(),
          safeText.getBytes(StandardCharsets.UTF_8).length,
          System.currentTimeMillis(),
          outputKind,
          replayPolicy,
          briefRenderPolicy,
          payloadType,
          payloadJson,
          0L);
    }

    /**
     * Returns this entry with the exact byte cost of its complete persisted transcript envelope.
     *
     * <p>The entry has no scope until a buffer implementation receives it. Measuring only rendered
     * text before that point lets structured metadata evade the configured retention bounds.
     */
    public BufferedEntry withCanonicalByteSize(
        long tenantId, long gameInstanceId, long characterId) {
      return new BufferedEntry(
          text,
          lineCount,
          canonicalByteSize(tenantId, gameInstanceId, characterId),
          appendedAtMs,
          outputKind,
          replayPolicy,
          briefRenderPolicy,
          payloadType,
          payloadJson,
          orderingToken);
    }

    public int canonicalByteSize(long tenantId, long gameInstanceId, long characterId) {
      return ResumeTranscriptCanonicalizer.byteSize(
          tenantId,
          gameInstanceId,
          characterId,
          orderingToken,
          appendedAtMs,
          outputKind,
          replayPolicy,
          briefRenderPolicy,
          payloadType,
          payloadJson,
          text);
    }

    public boolean hasStructuredOutput() {
      return outputKind != null
          && !outputKind.isBlank()
          && replayPolicy != null
          && !replayPolicy.isBlank()
          && briefRenderPolicy != null
          && !briefRenderPolicy.isBlank()
          && payloadType != null
          && !payloadType.isBlank()
          && payloadJson != null
          && !payloadJson.isBlank();
    }
  }

  record BufferedScreen(
      java.util.List<BufferedEntry> entries, int messageCount, int lineCount, long updatedAtMs) {
    public BufferedScreen {
      entries = java.util.List.copyOf(entries);
    }

    public String protocolText() {
      StringBuilder builder = new StringBuilder();
      for (BufferedEntry entry : entries) {
        builder.append(entry.text());
      }
      return builder.toString();
    }
  }
}
