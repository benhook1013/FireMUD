package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GameManifestDto;
import net.firedevops.firemud.entity.GameManifest;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameManifestMapper {
  GameManifestDto toDto(GameManifest entity);

  GameManifest toEntity(GameManifestDto dto);
}
