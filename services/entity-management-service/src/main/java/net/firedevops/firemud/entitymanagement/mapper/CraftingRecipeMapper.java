package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;
import net.firedevops.firemud.entitymanagement.entity.CraftingRecipe;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = CraftingIngredientMapper.class)
public interface CraftingRecipeMapper {
  @Mapping(target = "resultItemId", source = "resultItem.id")
  CraftingRecipeDto toDto(CraftingRecipe entity);

  @Mapping(target = "resultItem.id", source = "resultItemId")
  @Mapping(target = "versionId", ignore = true)
  CraftingRecipe toEntity(CraftingRecipeDto dto);
}
