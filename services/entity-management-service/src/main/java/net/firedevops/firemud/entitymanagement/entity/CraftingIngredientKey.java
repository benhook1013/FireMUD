package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.Data;

@Data
@Embeddable
public class CraftingIngredientKey implements Serializable {
  @Column(name = "recipe_id")
  private Long recipeId;

  @Column(name = "item_id")
  private Long itemId;
}
