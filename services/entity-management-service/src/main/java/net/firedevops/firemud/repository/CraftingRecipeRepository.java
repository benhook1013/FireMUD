package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.CraftingRecipe;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CraftingRecipeRepository extends JpaRepository<CraftingRecipe, Long> {
  @EntityGraph(attributePaths = {"ingredients", "ingredients.item", "resultItem"})
  CraftingRecipe findWithIngredientsById(Long id);
}
