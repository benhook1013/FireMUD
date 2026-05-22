package net.firedevops.firemud.common.temporal.config;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.common.temporal.TemporalWorkerRegistrar;
import org.springframework.context.SmartLifecycle;

final class TemporalWorkerHost implements SmartLifecycle {
  private final WorkerFactory workerFactory;
  private final WorkflowClient workflowClient;
  private final List<TemporalWorkerRegistrar> registrars;
  private volatile boolean running;
  private volatile boolean registered;

  TemporalWorkerHost(
      WorkerFactory workerFactory,
      WorkflowClient workflowClient,
      List<TemporalWorkerRegistrar> registrars) {
    this.workerFactory = workerFactory;
    this.workflowClient = workflowClient;
    this.registrars = registrars;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    if (!registered) {
      Set<String> queues = new LinkedHashSet<>();
      for (TemporalWorkerRegistrar registrar : registrars) {
        queues.addAll(registrar.registerWorkers(workerFactory, workflowClient));
      }
      registered = true;
      if (queues.isEmpty()) {
        return;
      }
    }
    workerFactory.start();
    running = true;
  }

  @Override
  public void stop() {
    if (!running) {
      return;
    }
    workerFactory.shutdown();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
