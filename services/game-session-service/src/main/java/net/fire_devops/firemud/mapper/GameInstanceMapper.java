package net.fire_devops.firemud.mapper;

import net.fire_devops.firemud.dto.GameInstanceDto;
import net.fire_devops.firemud.entity.GameInstance;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameInstanceMapper {
    GameInstanceDto toDto(GameInstance entity);
    GameInstance toEntity(GameInstanceDto dto);
}
