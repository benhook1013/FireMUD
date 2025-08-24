package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.CraftingIngredientDto;
import net.firedevops.firemud.entity.CraftingIngredient;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CraftingIngredientMapper {
  @Mapping(target = "itemId", source = "item.id")
  CraftingIngredientDto toDto(CraftingIngredient entity);

  @Mapping(target = "item.id", source = "itemId")
  @Mapping(target = "recipe", ignore = true)
  @Mapping(target = "id", ignore = true)
  CraftingIngredient toEntity(CraftingIngredientDto dto);
}
