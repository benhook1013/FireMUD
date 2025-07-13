package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.VersionDto;
import net.firedevops.firemud.entity.Version;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface VersionMapper {
  VersionDto toDto(Version entity);

  Version toEntity(VersionDto dto);
}
