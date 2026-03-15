package net.firedevops.firemud.worldmanagement.config;

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

  public void setRoom(Room room) {
    this.room = new Room(room);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Room properties are mutable and returned directly")
  public Room getRoom() {
    return room;
  }

  public static class Room {
    /** TTL in seconds for room cache entries. */
    @Getter @Setter private long cacheTtlSeconds = 60;

    public Room() {}

    public Room(Room other) {
      this.cacheTtlSeconds = other.cacheTtlSeconds;
    }
  }
}
