package net.firedevops.firemud.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.reconnection")
public record FiremudReconnectionProperties(Policy policy, Buffer buffer, ViewCache viewCache) {
  public FiremudReconnectionProperties {
    policy = policy == null ? new Policy(180_000L, true) : policy.normalize();
    buffer = buffer == null ? new Buffer(1_800_000L, 8, 24, 16_384, 65_536) : buffer.normalize();
    viewCache = viewCache == null ? new ViewCache(600_000L) : viewCache.normalize();
  }

  public record Policy(long resumeWindowMs, boolean staleResumeFallsThroughToFreshEntry) {
    Policy normalize() {
      return new Policy(
          resumeWindowMs > 0L ? resumeWindowMs : 180_000L, staleResumeFallsThroughToFreshEntry);
    }
  }

  public record Buffer(
      long ttlMs, int minMessages, int minLines, int softMaxBytes, int hardMaxBytes) {
    Buffer normalize() {
      return new Buffer(
          ttlMs > 0L ? ttlMs : 1_800_000L,
          minMessages > 0 ? minMessages : 8,
          minLines > 0 ? minLines : 24,
          softMaxBytes > 0 ? softMaxBytes : 16_384,
          hardMaxBytes > 0 ? hardMaxBytes : 65_536);
    }
  }

  public record ViewCache(long lookTtlMs) {
    ViewCache normalize() {
      return new ViewCache(lookTtlMs > 0L ? lookTtlMs : 600_000L);
    }
  }
}
