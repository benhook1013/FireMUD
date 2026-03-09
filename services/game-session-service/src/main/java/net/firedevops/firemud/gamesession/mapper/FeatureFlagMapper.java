package net.firedevops.firemud.gamesession.mapper;

import net.firedevops.firemud.gamesession.dto.FeatureFlagDto;
import net.firedevops.firemud.gamesession.entity.FeatureFlag;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FeatureFlagMapper {
  FeatureFlagDto toDto(FeatureFlag entity);

  FeatureFlag toEntity(FeatureFlagDto dto);
}
