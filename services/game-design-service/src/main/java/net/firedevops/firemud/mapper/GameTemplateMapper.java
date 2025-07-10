package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GameTemplateDto;
import net.firedevops.firemud.entity.GameTemplate;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameTemplateMapper {
  GameTemplateDto toDto(GameTemplate entity);

  GameTemplate toEntity(GameTemplateDto dto);
}
