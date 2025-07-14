package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ScriptDefinitionDto;
import net.firedevops.firemud.entity.ScriptDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ScriptDefinitionMapper {
  @Mapping(source = "scriptVersion", target = "version")
  ScriptDefinitionDto toDto(ScriptDefinition entity);

  @Mapping(source = "version", target = "scriptVersion")
  ScriptDefinition toEntity(ScriptDefinitionDto dto);
}
