package net.firedevops.firemud.socialgroups.mapper;

import net.firedevops.firemud.socialgroups.dto.GuildDto;
import net.firedevops.firemud.socialgroups.entity.Guild;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** MapStruct mapper for {@link Guild} entities. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildMapper {
  GuildDto toDto(Guild entity);

  Guild toEntity(GuildDto dto);
}
