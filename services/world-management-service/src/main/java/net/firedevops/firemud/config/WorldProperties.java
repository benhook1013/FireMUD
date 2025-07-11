package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for world settings such as the local shard. */
@Data
@ConfigurationProperties(prefix = "world")
public class WorldProperties {
  /** Identifier of the shard this service instance hosts. */
  private int localShardId = 0;
}
