package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VersionMapper {
  VersionDto toDto(Version entity);

  @Mapping(target = "game", ignore = true)
  Version toEntity(VersionDto dto);
}
