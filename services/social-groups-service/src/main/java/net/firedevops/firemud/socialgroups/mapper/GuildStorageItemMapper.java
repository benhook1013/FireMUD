package net.firedevops.firemud.socialgroups.mapper;

import net.firedevops.firemud.socialgroups.dto.GuildStorageItemDto;
import net.firedevops.firemud.socialgroups.entity.GuildStorageItem;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildStorageItemMapper {
  GuildStorageItemDto toDto(GuildStorageItem entity);

  GuildStorageItem toEntity(GuildStorageItemDto dto);
}
