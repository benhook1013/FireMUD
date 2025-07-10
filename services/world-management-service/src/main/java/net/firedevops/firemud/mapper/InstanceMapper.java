package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.InstanceDto;
import net.firedevops.firemud.entity.Instance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InstanceMapper {
  @Mapping(target = "zoneId", source = "zone.id")
  InstanceDto toDto(Instance entity);

  @Mapping(target = "zone.id", source = "zoneId")
  Instance toEntity(InstanceDto dto);
}
