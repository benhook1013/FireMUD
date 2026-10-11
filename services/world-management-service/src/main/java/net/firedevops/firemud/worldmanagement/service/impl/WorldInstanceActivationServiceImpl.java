package net.firedevops.firemud.worldmanagement.service.impl;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorldInstanceActivationServiceImpl implements WorldInstanceActivationService {
  private final WorldLifecycleCommandService commandService;
  private final Optional<TemporalWorldLifecycleOrchestrator> temporalOrchestrator;
  private final RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider;
  private final WorldInstanceRepository worldInstanceRepository;

  @Autowired
  public WorldInstanceActivationServiceImpl(
      WorldLifecycleCommandService commandService,
      Optional<TemporalWorldLifecycleOrchestrator> temporalOrchestrator,
      RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider,
      WorldInstanceRepository worldInstanceRepository) {
    this.commandService = commandService;
    this.temporalOrchestrator = temporalOrchestrator;
    this.runOwnedWorldLaunchEvidenceProvider = runOwnedWorldLaunchEvidenceProvider;
    this.worldInstanceRepository = worldInstanceRepository;
  }

  WorldInstanceActivationServiceImpl(
      WorldLifecycleCommandService commandService,
      Optional<TemporalWorldLifecycleOrchestrator> temporalOrchestrator) {
    this(commandService, temporalOrchestrator, null, null);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto prepareWorldInstance(
      PreparedWorldInstanceRequest request) {
    if (temporalOrchestrator.isPresent()) {
      requireFixtureCommandPath(
          request.tenantId(), request.gameInstanceId(), request.controlPlaneRequestId());
      return temporalOrchestrator.get().prepareWorldInstance(request);
    }
    return commandService.prepareWorldInstance(request);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch) {
    if (temporalOrchestrator.isPresent()) {
      requireFixtureCommandPath(tenantId, gameInstanceId, null);
      return temporalOrchestrator
          .get()
          .activatePreparedWorldInstance(tenantId, gameInstanceId, expectedLifecycleEpoch);
    }
    return commandService.activatePreparedWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch);
  }

  private void requireFixtureCommandPath(
      long tenantId, long gameInstanceId, String controlPlaneRequestId) {
    boolean fixtureConfigured =
        runOwnedWorldLaunchEvidenceProvider != null
            && runOwnedWorldLaunchEvidenceProvider.isEnabled();
    boolean fixtureBound =
        worldInstanceRepository != null
            && (worldInstanceRepository
                    .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                    .map(WorldInstance::getRunOwnedFixtureBindingSha256)
                    .filter(binding -> binding != null)
                    .isPresent()
                || (controlPlaneRequestId != null
                    && worldInstanceRepository
                        .findRunOwnedFixtureByTenantIdAndControlPlaneRequestId(
                            tenantId, controlPlaneRequestId)
                        .isPresent()));
    if (fixtureConfigured || fixtureBound) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_LAUNCH_TEMPORAL_UNSUPPORTED: fixture lifecycle requires the authenticated synchronous command path");
    }
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    if (temporalOrchestrator.isPresent()) {
      requireFixtureCommandPath(tenantId, gameInstanceId, null);
      return temporalOrchestrator
          .get()
          .failPreparedWorldInstance(tenantId, gameInstanceId, expectedLifecycleEpoch, reason);
    }
    return commandService.failPreparedWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch, reason);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto getWorldInstanceLifecycle(
      long tenantId, long gameInstanceId) {
    if (temporalOrchestrator.isPresent()) {
      requireFixtureCommandPath(tenantId, gameInstanceId, null);
    }
    WorldInstanceLifecycleSnapshotDto snapshot =
        commandService.getWorldInstanceLifecycle(tenantId, gameInstanceId);
    if (temporalOrchestrator.isPresent()) {
      return temporalOrchestrator
          .get()
          .getWorldInstanceLifecycle(tenantId, gameInstanceId, snapshot);
    }
    return snapshot;
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto terminateWorldInstance(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason) {
    if (temporalOrchestrator.isPresent()) {
      requireFixtureCommandPath(tenantId, gameInstanceId, null);
      return temporalOrchestrator
          .get()
          .terminateWorldInstance(
              tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId, reason);
    }
    return commandService.terminateWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId, reason);
  }
}
