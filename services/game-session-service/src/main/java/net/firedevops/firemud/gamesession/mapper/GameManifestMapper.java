package net.firedevops.firemud.gamesession.mapper;

import net.firedevops.firemud.gamesession.dto.GameManifestDto;
import net.firedevops.firemud.gamesession.entity.GameManifest;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameManifestMapper {
  GameManifestDto toDto(GameManifest entity);

  GameManifest toEntity(GameManifestDto dto);
}
