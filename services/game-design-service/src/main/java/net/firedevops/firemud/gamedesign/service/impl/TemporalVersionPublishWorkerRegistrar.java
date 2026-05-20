package net.firedevops.firemud.gamedesign.service.impl;

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
public class TemporalVersionPublishWorkerRegistrar implements TemporalWorkerRegistrar {
  private final TemporalTaskQueueResolver taskQueues;
  private final TemporalVersionPublishActivities activities;

  public TemporalVersionPublishWorkerRegistrar(
      TemporalTaskQueueResolver taskQueues, TemporalVersionPublishActivities activities) {
    this.taskQueues = taskQueues;
    this.activities = activities;
  }

  @Override
  public List<String> registerWorkers(WorkerFactory workerFactory, WorkflowClient workflowClient) {
    String taskQueue = taskQueues.forWorkflowFamily(TemporalVersionPublishWorkflow.WORKFLOW_FAMILY);
    Worker worker = workerFactory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(TemporalVersionPublishWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    return List.of(taskQueue);
  }
}
