package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class RoomExit {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private Room fromRoom;
  private Room toRoom;
  private String direction;
  private int cost = 1;

  private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Room getFromRoom() {
    return fromRoom;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setFromRoom(Room fromRoom) {
    this.fromRoom = fromRoom;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Room getToRoom() {
    return toRoom;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setToRoom(Room toRoom) {
    this.toRoom = toRoom;
  }
}
