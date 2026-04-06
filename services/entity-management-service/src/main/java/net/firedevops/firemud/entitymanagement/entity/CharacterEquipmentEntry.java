package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "character_equipment")
public class CharacterEquipmentEntry {
  @EmbeddedId private CharacterEquipmentKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("characterId")
  private Character character;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Version private int version;

  public CharacterEquipmentKey getId() {
    if (id == null) {
      return null;
    }
    CharacterEquipmentKey copy = new CharacterEquipmentKey();
    copy.setCharacterId(id.getCharacterId());
    copy.setSlot(id.getSlot());
    return copy;
  }

  public void setId(CharacterEquipmentKey id) {
    if (id == null) {
      this.id = null;
    } else {
      CharacterEquipmentKey copy = new CharacterEquipmentKey();
      copy.setCharacterId(id.getCharacterId());
      copy.setSlot(id.getSlot());
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
      this.item = copy;
    }
  }
}
