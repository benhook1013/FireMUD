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

  @Column(nullable = false)
  private Long versionId = 1L;

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

  @Enumerated(EnumType.STRING)
  @Column(name = "stack_compatibility_mode", nullable = false, length = 64)
  private ItemStackCompatibilityMode stackCompatibilityMode =
      ItemStackCompatibilityMode.DEFINITION_ONLY;

  @Column(name = "stack_variant_key", length = 128)
  private String stackVariantKey;

  @Version private int version;
}
