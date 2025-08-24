package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.VersionDto;
import net.firedevops.firemud.entity.Version;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VersionMapper {
  VersionDto toDto(Version entity);

  @Mapping(target = "game", ignore = true)
  Version toEntity(VersionDto dto);
}
