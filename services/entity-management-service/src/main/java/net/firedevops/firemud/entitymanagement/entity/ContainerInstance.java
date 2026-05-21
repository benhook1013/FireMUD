package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class ContainerInstance {
  private Long id;
  private Long tenantId;
  private Character character;
  private String equipmentSlot;
  private String gameInstanceId;
  private String roomInstanceId;
  private Item item;
  private ItemInstance itemInstance;

  private int version;

  public Character getCharacter() {
    if (character == null) {
      return null;
    }
    Character copy = new Character();
    copy.setId(character.getId());
    copy.setTenantId(character.getTenantId());
    copy.setAccountId(character.getAccountId());
    copy.setName(character.getName());
    return copy;
  }

  public void setCharacter(Character character) {
    if (character == null) {
      this.character = null;
    } else {
      Character copy = new Character();
      copy.setId(character.getId());
      copy.setTenantId(character.getTenantId());
      copy.setAccountId(character.getAccountId());
      copy.setName(character.getName());
      this.character = copy;
    }
  }

  public Item getItem() {
    if (item == null) {
      return null;
    }
    Item copy = new Item();
    copy.setId(item.getId());
    copy.setTenantId(item.getTenantId());
    copy.setName(item.getName());
    copy.setDescription(item.getDescription());
    copy.setEquipmentSlot(item.getEquipmentSlot());
    copy.setContainer(item.isContainer());
    copy.setStackable(item.isStackable());
    copy.setStackCompatibilityMode(item.getStackCompatibilityMode());
    copy.setStackVariantKey(item.getStackVariantKey());
    copy.setEffectPayloadJson(item.getEffectPayloadJson());
    return copy;
  }

  public void setItem(Item item) {
    if (item == null) {
      this.item = null;
    } else {
      Item copy = new Item();
      copy.setId(item.getId());
      copy.setTenantId(item.getTenantId());
      copy.setName(item.getName());
      copy.setDescription(item.getDescription());
      copy.setEquipmentSlot(item.getEquipmentSlot());
      copy.setContainer(item.isContainer());
      copy.setStackable(item.isStackable());
      copy.setStackCompatibilityMode(item.getStackCompatibilityMode());
      copy.setStackVariantKey(item.getStackVariantKey());
      copy.setEffectPayloadJson(item.getEffectPayloadJson());
      this.item = copy;
    }
  }
}
