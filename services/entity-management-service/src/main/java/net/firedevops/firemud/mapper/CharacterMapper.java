package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entity.Character;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CharacterMapper {
  CharacterDto toDto(Character entity);

  @Mapping(target = "inventoryEntries", ignore = true)
  @Mapping(target = "lastLoginAt", ignore = true)
  @Mapping(target = "version", ignore = true)
  Character toEntity(CharacterDto dto);
}
