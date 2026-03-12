package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.entity.Revision;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RevisionMapper {
  RevisionDto toDto(Revision entity);

  Revision toEntity(RevisionDto dto);
}
