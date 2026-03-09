package net.firedevops.firemud.gamesession.mapper;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GameInstanceMapper {
  GameInstanceDto toDto(GameInstance entity);

  @Mapping(target = "scriptPatchPinnedAt", ignore = true)
  @Mapping(target = "scriptPatchPinnedBy", ignore = true)
  @Mapping(target = "scriptPatchPinnedReason", ignore = true)
  GameInstance toEntity(GameInstanceDto dto);
}
