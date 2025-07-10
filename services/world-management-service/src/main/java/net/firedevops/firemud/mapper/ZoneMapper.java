package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ZoneDto;
import net.firedevops.firemud.entity.Zone;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ZoneMapper {
  @Mapping(target = "regionId", source = "region.id")
  ZoneDto toDto(Zone entity);

  @Mapping(target = "region.id", source = "regionId")
  Zone toEntity(ZoneDto dto);
}
