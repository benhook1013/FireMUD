package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Character {
  @EqualsAndHashCode.Include private Long id;
  private Long tenantId;
  private Long accountId;
  private String playableStateKey;
  private String name;
  private String bodyLayoutKey = "DEFAULT";

  private int level;
  private int experience;
  private int strength;
  private int agility;
  private int intelligence;
  private int stamina;
  private int health;
  private int mana;

  private Set<InventoryEntry> inventoryEntries = new HashSet<>();
  private Instant lastLoginAt;

  private int version;

  public Set<InventoryEntry> getInventoryEntries() {
    return Collections.unmodifiableSet(inventoryEntries);
  }

  public void setInventoryEntries(Set<InventoryEntry> inventoryEntries) {
    this.inventoryEntries = new HashSet<>(inventoryEntries);
  }
}
