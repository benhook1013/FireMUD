package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "container_contents")
public class ContainerContentEntry {
  @EmbeddedId private ContainerContentKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("characterId")
  private Character character;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("containerItemId")
  @JoinColumn(name = "container_item_id", nullable = false)
  private Item containerItem;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("itemId")
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Column(nullable = false)
  private int quantity;

  @Version private int version;

  public ContainerContentKey getId() {
    if (id == null) {
      return null;
    }
    ContainerContentKey copy = new ContainerContentKey();
    copy.setTenantId(id.getTenantId());
    copy.setCharacterId(id.getCharacterId());
    copy.setContainerItemId(id.getContainerItemId());
    copy.setItemId(id.getItemId());
    return copy;
  }

  public void setId(ContainerContentKey id) {
    if (id == null) {
      this.id = null;
    } else {
      ContainerContentKey copy = new ContainerContentKey();
      copy.setTenantId(id.getTenantId());
      copy.setCharacterId(id.getCharacterId());
      copy.setContainerItemId(id.getContainerItemId());
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

  public Item getContainerItem() {
    if (containerItem == null) {
      return null;
    }
    Item copy = new Item();
    copy.setId(containerItem.getId());
    copy.setTenantId(containerItem.getTenantId());
    copy.setName(containerItem.getName());
    copy.setDescription(containerItem.getDescription());
    copy.setEquipmentSlot(containerItem.getEquipmentSlot());
    copy.setContainer(containerItem.isContainer());
    return copy;
  }

  public void setContainerItem(Item containerItem) {
    if (containerItem == null) {
      this.containerItem = null;
    } else {
      Item copy = new Item();
      copy.setId(containerItem.getId());
      copy.setTenantId(containerItem.getTenantId());
      copy.setName(containerItem.getName());
      copy.setDescription(containerItem.getDescription());
      copy.setEquipmentSlot(containerItem.getEquipmentSlot());
      copy.setContainer(containerItem.isContainer());
      this.containerItem = copy;
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
