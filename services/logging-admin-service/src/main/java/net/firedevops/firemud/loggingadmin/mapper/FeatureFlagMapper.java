package net.firedevops.firemud.loggingadmin.mapper;

import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.entity.FeatureFlag;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FeatureFlagMapper {
  FeatureFlagDto toDto(FeatureFlag entity);

  FeatureFlag toEntity(FeatureFlagDto dto);
}
