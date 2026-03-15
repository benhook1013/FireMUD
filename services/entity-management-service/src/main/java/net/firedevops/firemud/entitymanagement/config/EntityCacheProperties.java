package net.firedevops.firemud.entitymanagement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for entity cache settings. */
@Data
@ConfigurationProperties(prefix = "entity.cache")
public class EntityCacheProperties {
  /** TTL in seconds for the characterGraph cache. */
  private long characterGraphTtlSeconds = 60;
}
