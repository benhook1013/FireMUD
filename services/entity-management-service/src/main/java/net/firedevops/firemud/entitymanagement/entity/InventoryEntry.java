package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "inventory")
public class InventoryEntry {
  @EmbeddedId private InventoryKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("characterId")
  private Character character;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("itemId")
  private Item item;

  @Column(nullable = false)
  private int quantity;

  @Version private int version;

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
    return character;
  }

  public void setCharacter(Character character) {
    this.character = character;
  }

  public Item getItem() {
    return item;
  }

  public void setItem(Item item) {
    this.item = item;
  }
}
