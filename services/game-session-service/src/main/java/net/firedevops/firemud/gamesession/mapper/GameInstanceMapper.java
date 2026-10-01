package net.firedevops.firemud.gamesession.mapper;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GameInstanceMapper {
  @Mapping(
      target = "scriptPinControlPlaneRequestId",
      source = "scriptPatchPinnedControlPlaneRequestId")
  @Mapping(target = "scriptPatchBaseVersionId", source = "scriptPatchBaseVersionId")
  GameInstanceDto toDto(GameInstance entity);
}
