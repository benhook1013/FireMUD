package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.config.LogOnlyProperties;
import net.firedevops.firemud.dto.FeatureFlagDto;
import net.firedevops.firemud.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.entity.FeatureFlag;
import net.firedevops.firemud.mapper.FeatureFlagMapper;
import net.firedevops.firemud.repository.FeatureFlagRepository;
import net.firedevops.firemud.service.FeatureFlagService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeatureFlagServiceImpl implements FeatureFlagService {
  private static final Logger logger = LoggingUtil.getLogger(FeatureFlagServiceImpl.class);

  private final FeatureFlagRepository repository;
  private final FeatureFlagMapper mapper;
  private final LogOnlyProperties logOnlyProperties;

  @Override
  @Timed(value = "gamesession.feature.toggle")
  @Transactional
  public FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request) {
    logger.info("Toggling feature flag {} for tenant {}", request.name(), request.tenantId());
    if (logOnlyProperties.isLogOnly()) {
      logger.info("Log-only mode enabled; acknowledging toggle without persistence");
      return new FeatureFlagDto(null, request.tenantId(), request.name(), request.enabled());
    }

    Optional<FeatureFlag> existing =
        repository.findByTenantIdAndName(request.tenantId(), request.name());
    FeatureFlag flag = existing.orElseGet(FeatureFlag::new);
    flag.setTenantId(request.tenantId());
    flag.setName(request.name());
    flag.setEnabled(request.enabled());
    flag = repository.save(flag);
    return mapper.toDto(flag);
  }
}
