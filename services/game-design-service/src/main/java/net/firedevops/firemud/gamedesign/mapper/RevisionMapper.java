package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.entity.Revision;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RevisionMapper {
  @Mapping(target = "worldDesignMutation", ignore = true)
  @Mapping(target = "appliedWorldDesignMutation", ignore = true)
  RevisionDto toDto(Revision entity);

  Revision toEntity(RevisionDto dto);
}
