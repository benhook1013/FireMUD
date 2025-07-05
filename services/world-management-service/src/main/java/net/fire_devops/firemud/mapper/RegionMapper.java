package net.fire_devops.firemud.mapper;

import net.fire_devops.firemud.dto.RegionDto;
import net.fire_devops.firemud.entity.Region;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RegionMapper {
    RegionDto toDto(Region entity);
    Region toEntity(RegionDto dto);
}
