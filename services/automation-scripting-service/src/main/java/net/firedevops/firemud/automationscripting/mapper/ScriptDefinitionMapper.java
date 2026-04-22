package net.firedevops.firemud.automationscripting.mapper;

import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ScriptDefinitionMapper {
  @Mapping(source = "scriptVersion", target = "version")
  @Mapping(target = "eventBindings", ignore = true)
  ScriptDefinitionDto toDto(ScriptDefinition entity);

  @Mapping(source = "version", target = "scriptVersion")
  @Mapping(target = "rowVersion", ignore = true)
  ScriptDefinition toEntity(ScriptDefinitionDto dto);
}
