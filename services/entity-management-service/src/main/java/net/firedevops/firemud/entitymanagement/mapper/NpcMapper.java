package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.NpcDto;
import net.firedevops.firemud.entitymanagement.entity.Npc;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface NpcMapper {
  @Mapping(
      target = "lastDefeatedAtEpochMs",
      expression =
          "java(entity.getLastDefeatedAt() == null ? null : entity.getLastDefeatedAt().toEpochMilli())")
  NpcDto toDto(Npc entity);

  @Mapping(
      target = "lastDefeatedAt",
      expression =
          "java(dto.lastDefeatedAtEpochMs() == null ? null : java.time.Instant.ofEpochMilli(dto.lastDefeatedAtEpochMs()))")
  @Mapping(target = "versionId", ignore = true)
  @Mapping(target = "version", ignore = true)
  Npc toEntity(NpcDto dto);
}
