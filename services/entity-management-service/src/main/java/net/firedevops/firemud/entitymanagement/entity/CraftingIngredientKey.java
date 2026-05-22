package net.firedevops.firemud.entitymanagement.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class CraftingIngredientKey implements Serializable {
  private Long recipeId;
  private Long itemId;
}
