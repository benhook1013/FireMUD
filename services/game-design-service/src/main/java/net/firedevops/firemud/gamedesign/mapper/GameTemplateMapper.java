package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.GameTemplateDto;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameTemplateMapper {
  GameTemplateDto toDto(GameTemplate entity);

  GameTemplate toEntity(GameTemplateDto dto);
}
