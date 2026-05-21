package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
public class InventoryEntry {
  private InventoryKey id;
  private Character character;
  private Item item;
  private int quantity;

  @EqualsAndHashCode.Exclude @ToString.Exclude private Long itemInstanceId;
  @EqualsAndHashCode.Exclude @ToString.Exclude private Long containerInstanceId;
  @EqualsAndHashCode.Exclude @ToString.Exclude private String visibleRef;

  private int version;

  public InventoryKey getId() {
    if (id == null) {
      return null;
    }
    InventoryKey copy = new InventoryKey();
    copy.setCharacterId(id.getCharacterId());
    copy.setItemId(id.getItemId());
    return copy;
  }

  public void setId(InventoryKey id) {
    if (id == null) {
      this.id = null;
    } else {
      InventoryKey copy = new InventoryKey();
      copy.setCharacterId(id.getCharacterId());
      copy.setItemId(id.getItemId());
      this.id = copy;
    }
  }

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
