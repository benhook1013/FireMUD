package net.firedevops.firemud.entity;

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
}
