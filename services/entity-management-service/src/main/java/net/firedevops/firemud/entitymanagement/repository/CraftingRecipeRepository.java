package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.CraftingRecipe;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CraftingRecipeRepository extends JpaRepository<CraftingRecipe, Long> {
  @EntityGraph(attributePaths = {"ingredients", "ingredients.item", "resultItem"})
  CraftingRecipe findWithIngredientsById(Long id);
}
