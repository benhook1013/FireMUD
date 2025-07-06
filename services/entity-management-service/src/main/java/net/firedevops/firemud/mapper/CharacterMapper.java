package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entity.Character;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CharacterMapper {
  CharacterDto toDto(Character entity);

  Character toEntity(CharacterDto dto);
}
