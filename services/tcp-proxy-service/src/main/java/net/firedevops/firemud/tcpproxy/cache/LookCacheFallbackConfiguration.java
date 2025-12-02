package net.firedevops.firemud.tcpproxy.cache;

import java.util.Optional;
import net.firedevops.firemud.cache.LookCacheService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "firemud.redis", name = "enabled", havingValue = "false")
@ConditionalOnMissingBean(LookCacheService.class)
public class LookCacheFallbackConfiguration {
  @Bean
  public LookCacheService noopLookCacheService() {
    return new NoopLookCacheService();
  }

  private static final class NoopLookCacheService implements LookCacheService {
    @Override
    public void cache(
        long tenantId, long sessionId, String roomId, String renderedText, String protocolText) {
      // intentionally no-op when Redis is disabled
    }

    @Override
    public Optional<CachedLook> get(long tenantId, long sessionId) {
      return Optional.empty();
    }
  }
}
