package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GuildStorageItemDto;
import net.firedevops.firemud.entity.GuildStorageItem;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildStorageItemMapper {
  GuildStorageItemDto toDto(GuildStorageItem entity);

  GuildStorageItem toEntity(GuildStorageItemDto dto);
}
