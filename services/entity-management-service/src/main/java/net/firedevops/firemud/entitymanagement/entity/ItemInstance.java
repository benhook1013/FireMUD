package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class ItemInstance {
  private Long id;
  private Long tenantId;
  private Character character;
  private String equipmentSlot;
  private String gameInstanceId;
  private String roomInstanceId;
  private ContainerInstance containerInstance;
  private Item item;
  private String visibleRefToken;
  private Long visibleRefSequence;
  private String visibleRef;

  private int version;
}
