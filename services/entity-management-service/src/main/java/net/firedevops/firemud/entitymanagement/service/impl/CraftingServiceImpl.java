package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;
import net.firedevops.firemud.entitymanagement.entity.CraftingRecipe;
import net.firedevops.firemud.entitymanagement.mapper.CraftingRecipeMapper;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import net.firedevops.firemud.entitymanagement.service.CraftingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CraftingServiceImpl implements CraftingService {
  private final CraftingRecipeRepository repository;
  private final CraftingRecipeMapper mapper;

  @Override
  @Transactional
  @Timed(value = "crafting.createRecipe")
  public CraftingRecipeDto createRecipe(CraftingRecipeDto dto) {
    CraftingRecipe entity = mapper.toEntity(dto);
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "crafting.getRecipe")
  public CraftingRecipeDto getRecipe(Long id) {
    CraftingRecipe entity = repository.findWithIngredientsById(id);
    return mapper.toDto(entity);
  }
}
