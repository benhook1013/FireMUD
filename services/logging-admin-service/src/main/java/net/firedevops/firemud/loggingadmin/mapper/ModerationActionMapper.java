package net.firedevops.firemud.loggingadmin.mapper;

import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ModerationActionMapper {
  ModerationActionDto toDto(ModerationAction entity);

  ModerationAction toEntity(ModerationActionDto dto);
}
