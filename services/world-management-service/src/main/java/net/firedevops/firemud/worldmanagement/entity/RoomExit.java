package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "room_exit")
public class RoomExit {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "from_room_id", nullable = false)
  private Room fromRoom;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "to_room_id", nullable = false)
  private Room toRoom;

  @Column(nullable = false)
  private int cost = 1;

  @Version private int version;

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
