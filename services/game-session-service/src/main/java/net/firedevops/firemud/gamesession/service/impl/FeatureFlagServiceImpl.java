package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.FeatureFlagDto;
import net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.gamesession.entity.FeatureFlag;
import net.firedevops.firemud.gamesession.mapper.FeatureFlagMapper;
import net.firedevops.firemud.gamesession.repository.FeatureFlagRepository;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeatureFlagServiceImpl implements FeatureFlagService {
  private static final Logger logger = LoggingUtil.getLogger(FeatureFlagServiceImpl.class);

  private final FeatureFlagRepository repository;
  private final FeatureFlagMapper mapper;

  @Override
  @Timed(value = "gamesession.feature.toggle")
  @Transactional
  public FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request) {
    logger.info("Toggling feature flag {} for tenant {}", request.name(), request.tenantId());

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
