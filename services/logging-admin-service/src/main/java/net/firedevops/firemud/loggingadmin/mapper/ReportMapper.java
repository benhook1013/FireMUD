package net.firedevops.firemud.loggingadmin.mapper;

import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.entity.PlayerReport;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReportMapper {
  ReportDto toDto(PlayerReport entity);

  PlayerReport toEntity(ReportDto dto);
}
