package net.firedevops.firemud.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for world settings such as the local shard. */
@ConfigurationProperties(prefix = "world")
public class WorldProperties {
  /** Identifier of the shard this service instance hosts. */
  @Getter @Setter private int localShardId = 0;

  /** Properties related to room behaviour. */
  private Room room = new Room();

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
  public void setRoom(Room room) {
    this.room = room;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP")
  public Room getRoom() {
    return room;
  }

  public static class Room {
    /** TTL in seconds for room cache entries. */
    @Getter @Setter private long cacheTtlSeconds = 60;
  }
}
