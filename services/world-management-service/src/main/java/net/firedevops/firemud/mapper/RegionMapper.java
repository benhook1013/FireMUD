package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RegionDto;
import net.firedevops.firemud.entity.Region;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RegionMapper {
    RegionDto toDto(Region entity);
    Region toEntity(RegionDto dto);
}
