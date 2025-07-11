package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ModerationActionDto;
import net.firedevops.firemud.entity.ModerationAction;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ModerationActionMapper {
  ModerationActionDto toDto(ModerationAction entity);

  ModerationAction toEntity(ModerationActionDto dto);
}
