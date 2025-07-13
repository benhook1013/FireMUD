package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GenerationRuleDto;
import net.firedevops.firemud.entity.GenerationRule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GenerationRuleMapper {
  GenerationRuleDto toDto(GenerationRule entity);

  @Mapping(target = "version", ignore = true)
  GenerationRule toEntity(GenerationRuleDto dto);
}
