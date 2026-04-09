package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "item_instances")
public class ItemInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "character_id")
  private Character character;

  @Column(name = "equipment_slot")
  private String equipmentSlot;

  @Column(name = "game_instance_id")
  private String gameInstanceId;

  @Column(name = "room_instance_id")
  private String roomInstanceId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "container_instance_id")
  private ContainerInstance containerInstance;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Version private int version;
}
