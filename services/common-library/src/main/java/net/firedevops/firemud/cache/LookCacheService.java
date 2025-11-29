package net.firedevops.firemud.cache;

import java.util.Optional;

/** Persists and retrieves serialized LOOK renderings for reconnects or replay scenarios. */
public interface LookCacheService {
  void cache(
      long tenantId, long sessionId, String roomId, String renderedText, String protocolText);

  Optional<CachedLook> get(long tenantId, long sessionId);

  record CachedLook(String roomId, String renderedText, String protocolText, long cachedAtMs) {}
}
