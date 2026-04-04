package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.service.WorldCreationService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates world creation using the shared Saga library. In a real implementation this would
 * copy design data from the Game Design Service and schedule initial world events.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Dependencies are injected and remain internal")
public class WorldCreationServiceImpl implements WorldCreationService {
  private final RegionRepository regionRepository;
  private final MeterRegistry meterRegistry;
  private final WorldProperties worldProperties;
  private final net.firedevops.firemud.worldmanagement.client.GameDesignClient gameDesignClient;
  private final SagaRunner sagaRunner;

  private Counter sagaStartedCounter;
  private Counter sagaFailedCounter;

  private static final Logger logger = LoggingUtil.getLogger(WorldCreationServiceImpl.class);

  @Autowired
  public WorldCreationServiceImpl(
      RegionRepository regionRepository,
      MeterRegistry meterRegistry,
      WorldProperties worldProperties,
      net.firedevops.firemud.worldmanagement.client.GameDesignClient gameDesignClient,
      SagaRunner sagaRunner) {
    this.regionRepository = regionRepository;
    this.meterRegistry = meterRegistry;
    this.worldProperties = worldProperties;
    this.gameDesignClient = gameDesignClient;
    this.sagaRunner = sagaRunner;
  }

  @PostConstruct
  void initMetrics() {
    sagaStartedCounter = meterRegistry.counter("world_creation_saga_started_total");
    sagaFailedCounter = meterRegistry.counter("world_creation_saga_failed_total");
  }

  private void ensureMetrics() {
    if (sagaStartedCounter == null || sagaFailedCounter == null) {
      initMetrics();
    }
  }

  @Override
  @Transactional
  @Timed(value = "world.create")
  public void createWorld(Long tenantId, Long versionId) throws SagaException {
    ensureMetrics();
    logger.info("Creating world for tenant {} from version {}", tenantId, versionId);
    sagaStartedCounter.increment();
    SagaBuilder builder = new SagaBuilder("createWorld");
    builder
        .step(
            "copyDesign",
            () -> copyDesignData(tenantId, versionId),
            () -> rollbackDesignCopy(tenantId))
        .step("scheduleEvents", () -> scheduleInitialEvents(tenantId));
    try {
      sagaRunner.run(builder.build());
    } catch (SagaException ex) {
      sagaFailedCounter.increment();
      logger.warn("World creation saga failed for tenant {}", tenantId);
      throw ex;
    }
  }

  private void copyDesignData(Long tenantId, Long versionId) {
    // Fetch the published version to verify connectivity with the Game Design Service.
    gameDesignClient.listVersions(tenantId);
    Region region = new Region();
    region.setTenantId(tenantId);
    // Newly created worlds start on the local shard. Admin tooling
    // redistributes regions for scaling.
    region.setShardId(worldProperties.getLocalShardId());
    region.setGenerationSeed(System.currentTimeMillis());
    region.setGeneratorType("SimpleDungeonGenerator");
    region.setGeneratorParams("{}");
    region.setSpacingMultiplier(1.0);
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
