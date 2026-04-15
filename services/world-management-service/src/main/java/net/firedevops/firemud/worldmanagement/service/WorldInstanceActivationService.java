package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;

public interface WorldInstanceActivationService {
  WorldInstanceLifecycleSnapshotDto prepareWorldInstance(PreparedWorldInstanceRequest request);

  WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch);

  WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason);
}
