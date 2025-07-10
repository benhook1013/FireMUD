package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.CraftingRecipeDto;
import net.firedevops.firemud.entity.CraftingRecipe;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CraftingRecipeMapper {
  @Mapping(target = "resultItemId", source = "resultItem.id")
  CraftingRecipeDto toDto(CraftingRecipe entity);

  @Mapping(target = "resultItem.id", source = "resultItemId")
  CraftingRecipe toEntity(CraftingRecipeDto dto);
}
