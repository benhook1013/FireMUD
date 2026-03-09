package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.entity.Revision;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RevisionMapper {
  RevisionDto toDto(Revision entity);

  @Mapping(target = "game", ignore = true)
  Revision toEntity(RevisionDto dto);
}
