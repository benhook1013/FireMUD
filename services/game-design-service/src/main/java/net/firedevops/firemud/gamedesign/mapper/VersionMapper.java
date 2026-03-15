package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface VersionMapper {
  VersionDto toDto(Version entity);

  Version toEntity(VersionDto dto);
}
