package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.NpcDto;
import net.firedevops.firemud.entity.Npc;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NpcMapper {
    NpcDto toDto(Npc entity);
    Npc toEntity(NpcDto dto);
}
