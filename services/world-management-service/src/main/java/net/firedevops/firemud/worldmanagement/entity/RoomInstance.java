package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
    name = "room_instance",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_room_instance_tenant_game_room",
          columnNames = {"tenant_id", "game_instance_id", "room_instance_id"})
    })
public class RoomInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "room_instance_id", nullable = false)
  private Long roomInstanceId;

  @Column(name = "template_room_id", nullable = false)
  private Long templateRoomId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "region_instance_id", nullable = false)
  private RegionInstance regionInstance;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 255)
  private String description;

  @Column(columnDefinition = "TEXT")
  private String nameLocalizedVariantsJson;

  @Column(columnDefinition = "TEXT")
  private String descriptionLocalizedVariantsJson;

  @Version private int version;
}
