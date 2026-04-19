package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "room_instance_exit")
public class RoomInstanceExit {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "from_room_instance_id", nullable = false)
  private RoomInstance fromRoomInstance;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "to_room_instance_id", nullable = false)
  private RoomInstance toRoomInstance;

  @Column(nullable = false, length = 32)
  private String direction;

  @Column(nullable = false)
  private int cost = 1;

  @Version private int version;
}
