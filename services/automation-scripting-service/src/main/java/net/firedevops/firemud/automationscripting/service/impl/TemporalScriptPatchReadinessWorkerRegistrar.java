package net.firedevops.firemud.automationscripting.service.impl;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.List;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.common.temporal.TemporalWorkerRegistrar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean({WorkflowClient.class, TemporalTaskQueueResolver.class})
public class TemporalScriptPatchReadinessWorkerRegistrar implements TemporalWorkerRegistrar {
  private final TemporalTaskQueueResolver taskQueues;
  private final TemporalScriptPatchReadinessActivities activities;

  public TemporalScriptPatchReadinessWorkerRegistrar(
      TemporalTaskQueueResolver taskQueues, TemporalScriptPatchReadinessActivities activities) {
    this.taskQueues = taskQueues;
    this.activities = activities;
  }

  @Override
  public List<String> registerWorkers(WorkerFactory workerFactory, WorkflowClient workflowClient) {
    String taskQueue =
        taskQueues.forWorkflowFamily(TemporalScriptPatchReadinessWorkflow.WORKFLOW_FAMILY);
    Worker worker = workerFactory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(TemporalScriptPatchReadinessWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    return List.of(taskQueue);
  }
}
