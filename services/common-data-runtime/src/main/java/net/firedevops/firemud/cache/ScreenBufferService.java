package net.firedevops.firemud.cache;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Optional;

/** Stores a bounded per-player transcript window for reconnect context restoration. */
public interface ScreenBufferService {
  void append(
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
    private static final DateTimeFormatter MILLIS_INSTANT_FORMATTER =
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final int JSON_CONTROL_CHARACTER_MAX = 0x1F;

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
      return canonicalEnvelope(tenantId, gameInstanceId, characterId)
          .getBytes(StandardCharsets.UTF_8)
          .length;
    }

    private String canonicalEnvelope(long tenantId, long gameInstanceId, long characterId) {
      StringBuilder envelope = new StringBuilder();
      envelope.append('{');
      appendMember(envelope, "briefRenderPolicy", briefRenderPolicy);
      envelope.append(',');
      appendNumberMember(envelope, "characterId", characterId);
      envelope.append(',');
      appendNumberMember(envelope, "gameInstanceId", gameInstanceId);
      envelope.append(',');
      appendMember(
          envelope,
          "occurredAt",
          MILLIS_INSTANT_FORMATTER.format(Instant.ofEpochMilli(appendedAtMs)));
      envelope.append(',');
      appendNumberMember(envelope, "orderingToken", orderingToken);
      envelope.append(',');
      appendMember(envelope, "outputKind", outputKind);
      envelope.append(',');
      appendJsonMember(envelope, "payload", payloadJson);
      envelope.append(',');
      appendMember(envelope, "payloadType", payloadType);
      envelope.append(',');
      appendMember(envelope, "renderedText", text);
      envelope.append(',');
      appendMember(envelope, "replayPolicy", replayPolicy);
      envelope.append(',');
      appendNumberMember(envelope, "tenantId", tenantId);
      envelope.append('}');
      return envelope.toString();
    }

    private static void appendMember(StringBuilder target, String name, String value) {
      appendJsonString(target, name);
      target.append(':');
      if (value == null) {
        target.append("null");
        return;
      }
      appendJsonString(target, value);
    }

    private static void appendNumberMember(StringBuilder target, String name, long value) {
      appendJsonString(target, name);
      target.append(':').append(value);
    }

    private static void appendJsonMember(StringBuilder target, String name, String json) {
      appendJsonString(target, name);
      target.append(':');
      if (json == null || json.isBlank()) {
        target.append("null");
        return;
      }
      target.append(json);
    }

    private static void appendJsonString(StringBuilder target, String value) {
      target.append('"');
      String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
      for (int index = 0; index < normalized.length(); index++) {
        char character = normalized.charAt(index);
        switch (character) {
          case '"' -> target.append("\\\"");
          case '\\' -> target.append("\\\\");
          case '\b' -> target.append("\\b");
          case '\f' -> target.append("\\f");
          case '\n' -> target.append("\\n");
          case '\r' -> target.append("\\r");
          case '\t' -> target.append("\\t");
          default -> {
            if (character <= JSON_CONTROL_CHARACTER_MAX) {
              target.append(String.format("\\u%04x", (int) character));
            } else {
              target.append(character);
            }
          }
        }
      }
      target.append('"');
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
