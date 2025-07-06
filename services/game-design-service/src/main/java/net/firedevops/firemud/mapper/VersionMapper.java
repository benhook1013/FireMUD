package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.VersionDto;
import net.firedevops.firemud.entity.Version;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VersionMapper {
  @Mapping(
      target = "gameId",
      expression = "java(entity.getGame() != null ? entity.getGame().getId() : null)")
  VersionDto toDto(Version entity);

  Version toEntity(VersionDto dto);
}
