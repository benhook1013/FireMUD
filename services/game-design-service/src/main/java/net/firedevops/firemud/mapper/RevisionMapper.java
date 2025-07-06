package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RevisionDto;
import net.firedevops.firemud.entity.Revision;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RevisionMapper {
  @Mapping(
      target = "gameId",
      expression = "java(entity.getGame() != null ? entity.getGame().getId() : null)")
  RevisionDto toDto(Revision entity);

  Revision toEntity(RevisionDto dto);
}
