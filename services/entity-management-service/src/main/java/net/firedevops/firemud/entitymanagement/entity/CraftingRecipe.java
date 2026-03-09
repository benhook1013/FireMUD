package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "crafting_recipes")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NamedEntityGraph(
    name = "recipe.ingredients",
    attributeNodes = {@NamedAttributeNode("ingredients"), @NamedAttributeNode("ingredients.item")})
public class CraftingRecipe {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @EqualsAndHashCode.Include
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "result_item_id", nullable = false)
  private Item resultItem;

  @Column(name = "result_quantity", nullable = false)
  private int resultQuantity;

  @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
  private Set<CraftingIngredient> ingredients = new HashSet<>();

  public Set<CraftingIngredient> getIngredients() {
    return Collections.unmodifiableSet(ingredients);
  }

  public void setIngredients(Set<CraftingIngredient> ingredients) {
    this.ingredients = new HashSet<>(ingredients);
  }

  public Item getResultItem() {
    if (resultItem == null) {
      return null;
    }
    Item copy = new Item();
    copy.setId(resultItem.getId());
    return copy;
  }

  public void setResultItem(Item resultItem) {
    if (resultItem == null) {
      this.resultItem = null;
    } else {
      Item copy = new Item();
      copy.setId(resultItem.getId());
      this.resultItem = copy;
    }
  }
}
