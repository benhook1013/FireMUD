package net.firedevops.firemud.worldmanagement.service.impl;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import org.springframework.stereotype.Service;

@Service
public class WorldInstanceActivationServiceImpl implements WorldInstanceActivationService {
  private final WorldLifecycleCommandService commandService;
  private final Optional<TemporalWorldLifecycleOrchestrator> temporalOrchestrator;

  public WorldInstanceActivationServiceImpl(
      WorldLifecycleCommandService commandService,
      Optional<TemporalWorldLifecycleOrchestrator> temporalOrchestrator) {
    this.commandService = commandService;
    this.temporalOrchestrator = temporalOrchestrator;
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto prepareWorldInstance(
      PreparedWorldInstanceRequest request) {
    if (temporalOrchestrator.isPresent()) {
      return temporalOrchestrator.get().prepareWorldInstance(request);
    }
    return commandService.prepareWorldInstance(request);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch) {
    if (temporalOrchestrator.isPresent()) {
      return temporalOrchestrator
          .get()
          .activatePreparedWorldInstance(tenantId, gameInstanceId, expectedLifecycleEpoch);
    }
    return commandService.activatePreparedWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch);
  }

  @Override
  public WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    if (temporalOrchestrator.isPresent()) {
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
      return temporalOrchestrator
          .get()
          .terminateWorldInstance(
              tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId, reason);
    }
    return commandService.terminateWorldInstance(
        tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId, reason);
  }
}
