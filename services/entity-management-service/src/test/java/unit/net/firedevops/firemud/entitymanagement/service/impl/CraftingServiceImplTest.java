package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;
import net.firedevops.firemud.entitymanagement.entity.CraftingRecipe;
import net.firedevops.firemud.entitymanagement.mapper.CraftingRecipeMapper;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class CraftingServiceImplTest {
  @Test
  void createRecipeReturnsDto() {
    CraftingRecipeRepository repo = Mockito.mock(CraftingRecipeRepository.class);
    CraftingRecipeMapper mapper = Mappers.getMapper(CraftingRecipeMapper.class);
    CraftingServiceImpl service = new CraftingServiceImpl(repo, mapper);

    CraftingRecipe saved = new CraftingRecipe();
    saved.setId(1L);
    saved.setTenantId(1L);
    saved.setName("Potion");
    when(repo.save(any(CraftingRecipe.class))).thenReturn(saved);

    CraftingRecipeDto dto = new CraftingRecipeDto(null, 1L, "Potion", 1L, 1, java.util.List.of());
    CraftingRecipeDto result = service.createRecipe(dto);
    assertEquals(1L, result.id());
  }
}
