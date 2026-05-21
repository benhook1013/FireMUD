package net.firedevops.firemud.entitymanagement.entity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CraftingRecipe {
  @EqualsAndHashCode.Include private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private String name;
  private Item resultItem;
  private int resultQuantity;
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
