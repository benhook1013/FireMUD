package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ReportDto;
import net.firedevops.firemud.entity.PlayerReport;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReportMapper {
  ReportDto toDto(PlayerReport entity);

  PlayerReport toEntity(ReportDto dto);
}
