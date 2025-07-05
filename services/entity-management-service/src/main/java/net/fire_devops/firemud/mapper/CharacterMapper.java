package net.fire_devops.firemud.mapper;

import net.fire_devops.firemud.dto.CharacterDto;
import net.fire_devops.firemud.entity.Character;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CharacterMapper {
    CharacterDto toDto(Character entity);
    Character toEntity(CharacterDto dto);
}
