package net.firedevops.firemud.cache;

import java.util.Optional;

/** Stores a bounded per-player transcript window for reconnect context restoration. */
public interface ScreenBufferService {
  void append(long tenantId, long gameInstanceId, long characterId, String protocolText);

  Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId);

  void clear(long tenantId, long gameInstanceId, long characterId);

  record BufferedScreen(String protocolText, int messageCount, int lineCount, long updatedAtMs) {}
}
