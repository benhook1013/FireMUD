package net.firedevops.firemud.loggingadmin.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.entity.FeatureFlag;
import net.firedevops.firemud.loggingadmin.mapper.FeatureFlagMapper;
import net.firedevops.firemud.loggingadmin.repository.FeatureFlagRepository;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureFlagServiceImpl implements FeatureFlagService {
  private static final Logger logger = LoggingUtil.getLogger(FeatureFlagServiceImpl.class);

  private final FeatureFlagRepository repository;
  private final FeatureFlagMapper mapper;

  public FeatureFlagServiceImpl(FeatureFlagRepository repository, FeatureFlagMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  @Timed(value = "loggingadmin.feature.toggle")
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
