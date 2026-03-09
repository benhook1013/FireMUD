package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;

public interface CraftingService {
  CraftingRecipeDto createRecipe(CraftingRecipeDto dto);

  CraftingRecipeDto getRecipe(Long id);
}
