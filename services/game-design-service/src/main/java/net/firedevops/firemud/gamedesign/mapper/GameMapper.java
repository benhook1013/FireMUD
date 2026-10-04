package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.GameDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameMapper {
  GameDto toDto(Game entity);

  Game toEntity(GameDto dto);
}
