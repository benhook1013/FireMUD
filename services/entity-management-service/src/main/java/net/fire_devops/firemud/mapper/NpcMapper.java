package net.fire_devops.firemud.mapper;

import net.fire_devops.firemud.dto.NpcDto;
import net.fire_devops.firemud.entity.Npc;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NpcMapper {
    NpcDto toDto(Npc entity);
    Npc toEntity(NpcDto dto);
}
