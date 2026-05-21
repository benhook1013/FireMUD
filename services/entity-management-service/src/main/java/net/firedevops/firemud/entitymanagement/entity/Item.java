package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class Item {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private String name;
  private String description;
  private String equipmentSlot;
  private String equipmentSlotGroupKey;
  private boolean container;
  private boolean stackable;
  private ItemStackCompatibilityMode stackCompatibilityMode =
      ItemStackCompatibilityMode.DEFINITION_ONLY;
  private String stackVariantKey;
  private String effectPayloadJson;

  private int version;
}
