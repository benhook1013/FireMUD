package net.firedevops.firemud.worldmanagement.mapper;

import net.firedevops.firemud.worldmanagement.dto.GenerationRuleDto;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GenerationRuleMapper {
  GenerationRuleDto toDto(GenerationRule entity);

  @Mapping(target = "version", ignore = true)
  GenerationRule toEntity(GenerationRuleDto dto);
}
