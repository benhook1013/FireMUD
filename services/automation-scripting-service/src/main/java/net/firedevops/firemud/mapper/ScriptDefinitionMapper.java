package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ScriptDefinitionDto;
import net.firedevops.firemud.entity.ScriptDefinition;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ScriptDefinitionMapper {
    ScriptDefinitionDto toDto(ScriptDefinition entity);
    ScriptDefinition toEntity(ScriptDefinitionDto dto);
}
