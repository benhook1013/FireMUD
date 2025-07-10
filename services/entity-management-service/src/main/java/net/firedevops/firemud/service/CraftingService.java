package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.CraftingRecipeDto;

public interface CraftingService {
  CraftingRecipeDto createRecipe(CraftingRecipeDto dto);

  CraftingRecipeDto getRecipe(Long id);
}
