package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.FeatureFlagDto;
import net.firedevops.firemud.entity.FeatureFlag;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FeatureFlagMapper {
  FeatureFlagDto toDto(FeatureFlag entity);

  FeatureFlag toEntity(FeatureFlagDto dto);
}
