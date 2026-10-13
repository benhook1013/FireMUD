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

  @Mapping(target = "scriptPatchBaseVersionId", ignore = true)
  @Mapping(target = "scriptPatchPinnedAt", ignore = true)
  @Mapping(target = "scriptPinEpoch", ignore = true)
  @Mapping(target = "scriptPatchPinnedBy", ignore = true)
  @Mapping(target = "scriptPatchPinnedReason", ignore = true)
  @Mapping(target = "scriptPatchPinnedControlPlaneRequestId", ignore = true)
  @Mapping(target = "rowVersion", ignore = true)
  @Mapping(target = "runOwnedStartRequestId", ignore = true)
  @Mapping(target = "runOwnedStartRequestDigest", ignore = true)
  @Mapping(target = "runOwnedStartPublishedReleaseBundleRef", ignore = true)
  @Mapping(target = "runOwnedStartPreparingEpoch", ignore = true)
  @Mapping(target = "runOwnedStartActiveEpoch", ignore = true)
  GameInstance toEntity(GameInstanceDto dto);
}
