package net.firedevops.firemud.entitymanagement.entity;

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

  public CraftingRecipe getRecipe() {
    if (recipe == null) {
      return null;
    }
    CraftingRecipe copy = new CraftingRecipe();
    copy.setId(recipe.getId());
    return copy;
  }

  public void setRecipe(CraftingRecipe recipe) {
    if (recipe == null) {
      this.recipe = null;
    } else {
      CraftingRecipe copy = new CraftingRecipe();
      copy.setId(recipe.getId());
      this.recipe = copy;
    }
  }

  public Item getItem() {
    if (item == null) {
      return null;
    }
    Item copy = new Item();
    copy.setId(item.getId());
    return copy;
  }

  public void setItem(Item item) {
    if (item == null) {
      this.item = null;
    } else {
      Item copy = new Item();
      copy.setId(item.getId());
      this.item = copy;
    }
  }
}
