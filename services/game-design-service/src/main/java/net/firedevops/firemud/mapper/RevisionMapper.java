package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RevisionDto;
import net.firedevops.firemud.entity.Revision;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RevisionMapper {
  RevisionDto toDto(Revision entity);

  @Mapping(target = "game", ignore = true)
  Revision toEntity(RevisionDto dto);
}
