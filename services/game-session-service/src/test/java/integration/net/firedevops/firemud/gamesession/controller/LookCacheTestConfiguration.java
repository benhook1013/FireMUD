package net.firedevops.firemud.gamesession.controller;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.cache.LookCacheService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class LookCacheTestConfiguration {

  @Bean
  LookCacheService lookCacheService() {
    return new InMemoryLookCacheService();
  }

  private static final class InMemoryLookCacheService implements LookCacheService {
    private final Map<String, CachedLook> cache = new ConcurrentHashMap<>();

    @Override
    public void cache(
        long tenantId, long sessionId, String roomId, String renderedText, String protocolText) {
      cache.put(
          key(tenantId, sessionId),
          new CachedLook(roomId, renderedText, protocolText, System.currentTimeMillis()));
    }

    @Override
    public Optional<CachedLook> get(long tenantId, long sessionId) {
      return Optional.ofNullable(cache.get(key(tenantId, sessionId)));
    }

    private String key(long tenantId, long sessionId) {
      return tenantId + ":" + sessionId;
    }
  }
}
