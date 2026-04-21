package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CharacterMapper {
  CharacterDto toDto(Character entity);

  @Mapping(target = "inventoryEntries", ignore = true)
  @Mapping(target = "lastLoginAt", ignore = true)
  @Mapping(target = "playableStateKey", ignore = true)
  @Mapping(target = "bodyLayoutKey", constant = "DEFAULT")
  @Mapping(target = "version", ignore = true)
  Character toEntity(CharacterDto dto);
}
