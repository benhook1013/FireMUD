package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GuildAllianceDto;
import net.firedevops.firemud.entity.GuildAlliance;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildAllianceMapper {
  GuildAllianceDto toDto(GuildAlliance entity);

  GuildAlliance toEntity(GuildAllianceDto dto);
}
