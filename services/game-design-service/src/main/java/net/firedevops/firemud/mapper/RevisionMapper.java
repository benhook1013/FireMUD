package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RevisionDto;
import net.firedevops.firemud.entity.Revision;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RevisionMapper {
  RevisionDto toDto(Revision entity);

  Revision toEntity(RevisionDto dto);
}
