package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class ItemStack {
  private Long id;
  private Long tenantId;
  private Character character;
  private String equipmentSlot;
  private String gameInstanceId;
  private String roomInstanceId;
  private ContainerInstance containerInstance;
  private Item item;
  private String compatibilityFingerprint;
  private String stackFamilyKey;
  private int quantity;

  private int version;
}
