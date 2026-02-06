package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.entity.GameInstance;
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
