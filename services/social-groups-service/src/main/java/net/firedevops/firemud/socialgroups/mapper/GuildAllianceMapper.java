package net.firedevops.firemud.socialgroups.mapper;

import net.firedevops.firemud.socialgroups.dto.GuildAllianceDto;
import net.firedevops.firemud.socialgroups.entity.GuildAlliance;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildAllianceMapper {
  GuildAllianceDto toDto(GuildAlliance entity);

  GuildAlliance toEntity(GuildAllianceDto dto);
}
