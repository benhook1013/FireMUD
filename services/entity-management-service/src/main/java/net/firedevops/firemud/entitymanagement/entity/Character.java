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
  private ActorIdentity actorIdentity;
  private Long tenantId;

  /** Retained private source-row evidence; never a public Account selector. */
  private Long accountId;

  private java.util.UUID accountUuid;

  public void setAccountUuid(java.util.UUID value) {
    if (value != null && value.equals(new java.util.UUID(0L, 0L))) {
      throw new IllegalArgumentException("Account UUID must not be nil");
    }
    if (value != null
        && actorIdentity != null
        && actorIdentity.accountUuid() != null
        && !value.equals(actorIdentity.accountUuid())) {
      throw new IllegalArgumentException("Account owner conflicts with actor identity");
    }
    accountUuid = value;
  }

  public void setActorIdentity(ActorIdentity value) {
    if (value != null
        && accountUuid != null
        && value.accountUuid() != null
        && !accountUuid.equals(value.accountUuid())) {
      throw new IllegalArgumentException("Actor identity conflicts with Account owner");
    }
    actorIdentity = value;
  }

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
