package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "items")
public class Item {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 255)
  private String description;

  @Column(name = "equipment_slot", length = 32)
  private String equipmentSlot;

  @Column(name = "is_container", nullable = false)
  private boolean container;

  @Column(name = "is_stackable", nullable = false)
  private boolean stackable;

  @Version private int version;
}
