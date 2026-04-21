package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedSubgraph;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "characters")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NamedEntityGraph(
    name = "character.inventory",
    attributeNodes = @NamedAttributeNode(value = "inventoryEntries", subgraph = "inventory.item"),
    subgraphs =
        @NamedSubgraph(name = "inventory.item", attributeNodes = @NamedAttributeNode("item")))
public class Character {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @EqualsAndHashCode.Include
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long accountId;

  @Column(nullable = false, length = 120)
  private String playableStateKey;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false, length = 64)
  private String bodyLayoutKey = "DEFAULT";

  private int level;

  @Column(nullable = false)
  private int experience;

  @Column(nullable = false)
  private int strength;

  @Column(nullable = false)
  private int agility;

  @Column(nullable = false)
  private int intelligence;

  @Column(nullable = false)
  private int stamina;

  @Column(nullable = false)
  private int health;

  @Column(nullable = false)
  private int mana;

  @OneToMany(
      mappedBy = "character",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private Set<InventoryEntry> inventoryEntries = new HashSet<>();

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Version private int version;

  public Set<InventoryEntry> getInventoryEntries() {
    return Collections.unmodifiableSet(inventoryEntries);
  }

  public void setInventoryEntries(Set<InventoryEntry> inventoryEntries) {
    this.inventoryEntries = new HashSet<>(inventoryEntries);
  }
}
