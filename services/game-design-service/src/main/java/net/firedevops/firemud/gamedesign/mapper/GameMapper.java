package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.GameDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GameMapper {
  GameDto toDto(Game entity);

  @Mapping(target = "canonicalTenantId", ignore = true)
  Game toEntity(GameDto dto);
}
