package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GameDto;
import net.firedevops.firemud.entity.Game;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameMapper {
    GameDto toDto(Game entity);
    Game toEntity(GameDto dto);
}
