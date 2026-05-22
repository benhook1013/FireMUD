package net.firedevops.firemud.worldmanagement.service.impl;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;

@WorkflowInterface
public interface TemporalWorldLifecycleWorkflow {
  String WORKFLOW_FAMILY = "world-lifecycle";

  @WorkflowMethod
  WorldInstanceLifecycleSnapshotDto run(PreparedWorldInstanceRequest request);

  @SignalMethod
  void activate(long expectedLifecycleEpoch);

  @SignalMethod
  void fail(long expectedLifecycleEpoch, String reason);

  @SignalMethod
  void terminate(long expectedLifecycleEpoch, String terminationRequestId, String reason);

  @QueryMethod
  WorldInstanceLifecycleSnapshotDto currentSnapshot();
}
