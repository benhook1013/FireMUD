package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.entity.Region;
import net.firedevops.firemud.repository.RegionRepository;
import net.firedevops.firemud.service.WorldCreationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates world creation using the shared Saga library. In a real implementation this would
 * copy design data from the Game Design Service and schedule initial world events.
 */
@Service
@RequiredArgsConstructor
public class WorldCreationServiceImpl implements WorldCreationService {
  private final RegionRepository regionRepository;
  private static final Logger logger = LoggingUtil.getLogger(WorldCreationServiceImpl.class);

  @Override
  @Transactional
  public void createWorld(Long tenantId, Long versionId) throws SagaException {
    logger.info("Creating world for tenant {} from version {}", tenantId, versionId);
    SagaBuilder builder = new SagaBuilder();
    builder
        .step(
            "copyDesign",
            () -> copyDesignData(tenantId, versionId),
            () -> rollbackDesignCopy(tenantId))
        .step("scheduleEvents", () -> scheduleInitialEvents(tenantId));
    builder.run();
  }

  private void copyDesignData(Long tenantId, Long versionId) {
    // TODO fetch data from Game Design Service. For now create a single region.
    Region region = new Region();
    region.setTenantId(tenantId);
    region.setName("Starter Region");
    regionRepository.save(region);
  }

  private void rollbackDesignCopy(Long tenantId) {
    regionRepository.deleteByTenantId(tenantId);
  }

  private void scheduleInitialEvents(Long tenantId) {
    // Placeholder for event scheduling logic.
    logger.debug("Scheduling initial events for tenant {}", tenantId);
  }
}
