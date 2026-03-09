package net.firedevops.firemud.worldmanagement.mapper;

import net.firedevops.firemud.worldmanagement.dto.InstanceDto;
import net.firedevops.firemud.worldmanagement.entity.Instance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InstanceMapper {
  @Mapping(target = "zoneId", source = "zone.id")
  InstanceDto toDto(Instance entity);

  @Mapping(target = "zone.id", source = "zoneId")
  @Mapping(target = "version", ignore = true)
  Instance toEntity(InstanceDto dto);
}
