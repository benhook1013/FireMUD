package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "crafting_ingredients")
public class CraftingIngredient {
  @EmbeddedId private CraftingIngredientKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("recipeId")
  private CraftingRecipe recipe;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("itemId")
  private Item item;

  @Column(nullable = false)
  private int quantity;

  public CraftingIngredientKey getId() {
    if (id == null) {
      return null;
    }
    CraftingIngredientKey copy = new CraftingIngredientKey();
    copy.setRecipeId(id.getRecipeId());
    copy.setItemId(id.getItemId());
    return copy;
  }

  public void setId(CraftingIngredientKey id) {
    if (id == null) {
      this.id = null;
    } else {
      CraftingIngredientKey copy = new CraftingIngredientKey();
      copy.setRecipeId(id.getRecipeId());
      copy.setItemId(id.getItemId());
      this.id = copy;
    }
  }
}
