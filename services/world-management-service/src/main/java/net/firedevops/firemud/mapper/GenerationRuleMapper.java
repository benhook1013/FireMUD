package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GenerationRuleDto;
import net.firedevops.firemud.entity.GenerationRule;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GenerationRuleMapper {
  GenerationRuleDto toDto(GenerationRule entity);

  GenerationRule toEntity(GenerationRuleDto dto);
}
