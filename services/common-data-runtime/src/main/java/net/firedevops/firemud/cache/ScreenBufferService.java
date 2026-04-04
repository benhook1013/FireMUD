package net.firedevops.firemud.cache;

import java.util.Optional;

/** Stores a bounded per-player transcript window for reconnect context restoration. */
public interface ScreenBufferService {
  void append(
      long tenantId, long gameInstanceId, long characterId, java.util.List<BufferedEntry> entries);

  Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId);

  void clear(long tenantId, long gameInstanceId, long characterId);

  record BufferedEntry(String text, int lineCount, int byteSize, long appendedAtMs) {
    public BufferedEntry {
      text = text == null ? "" : text;
    }

    public static BufferedEntry fromText(String text) {
      String safeText = text == null ? "" : text;
      return new BufferedEntry(
          safeText,
          (int) safeText.lines().filter(line -> !line.isBlank()).count(),
          safeText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
          System.currentTimeMillis());
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
