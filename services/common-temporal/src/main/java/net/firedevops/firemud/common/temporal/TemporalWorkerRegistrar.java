package net.firedevops.firemud.common.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import java.util.Collection;

public interface TemporalWorkerRegistrar {
  Collection<String> registerWorkers(WorkerFactory workerFactory, WorkflowClient workflowClient);
}
