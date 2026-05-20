package net.firedevops.firemud.worldmanagement.service.impl;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;

@ActivityInterface
public interface TemporalWorldLifecycleActivities {
  @ActivityMethod
  WorldInstanceLifecycleSnapshotDto prepareWorldInstance(PreparedWorldInstanceRequest request);

  @ActivityMethod
  WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch);

  @ActivityMethod
  WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason);

  @ActivityMethod
  WorldInstanceLifecycleSnapshotDto terminateWorldInstance(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason);
}
