package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for world settings such as the local shard. */
@Data
@ConfigurationProperties(prefix = "world")
public class WorldProperties {
  /** Identifier of the shard this service instance hosts. */
  private int localShardId = 0;

  /** Properties related to room behaviour. */
  private Room room = new Room();

  @Data
  public static class Room {
    /** TTL in seconds for room cache entries. */
    private long cacheTtlSeconds = 60;
  }

  public Room getRoom() {
    return room;
  }
}
