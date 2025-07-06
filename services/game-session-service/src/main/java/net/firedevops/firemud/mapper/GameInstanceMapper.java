package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.entity.GameInstance;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameInstanceMapper {
  GameInstanceDto toDto(GameInstance entity);

  GameInstance toEntity(GameInstanceDto dto);
}
