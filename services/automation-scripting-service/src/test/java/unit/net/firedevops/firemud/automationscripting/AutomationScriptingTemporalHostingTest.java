package net.firedevops.firemud.automationscripting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.List;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.common.temporal.TemporalWorkerRegistrar;
import net.firedevops.firemud.common.temporal.config.CommonTemporalAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AutomationScriptingTemporalHostingTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CommonTemporalAutoConfiguration.class))
          .withUserConfiguration(TestTemporalHostConfiguration.class)
          .withPropertyValues(
              "spring.application.name=automation-scripting-service",
              "firemud.temporal.enabled=true",
              "firemud.temporal.namespace=firemud-test",
              "firemud.temporal.workers-enabled=true",
              "firemud.temporal.task-queue-prefix=firemud");

  @Test
  void automationScriptingModuleUsesSharedTemporalHostingPattern() {
    contextRunner.run(
        context -> {
          TemporalTaskQueueResolver taskQueues = context.getBean(TemporalTaskQueueResolver.class);
          WorkerFactory workerFactory = context.getBean(WorkerFactory.class);
          Worker worker = context.getBean(Worker.class);
          String taskQueue = taskQueues.forWorkflowFamily("script-patch-readiness");

          assertThat(taskQueue)
              .isEqualTo("firemud:automation-scripting-service:script-patch-readiness");
          assertThat(
                  FiremudWorkflowIds.workflowId(
                      "script-patch-readiness", "tenant-1", "script-patch-version", "patch-1"))
              .isEqualTo("script-patch-readiness:tenant-1:script-patch-version:patch-1");

          verify(workerFactory).newWorker(taskQueue);
          verify(worker).registerWorkflowImplementationTypes(HostProbeWorkflowImpl.class);
          verify(workerFactory).start();
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class TestTemporalHostConfiguration {
    @Bean
    WorkflowServiceStubs workflowServiceStubs() {
      return mock(WorkflowServiceStubs.class);
    }

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    Worker worker() {
      return mock(Worker.class);
    }

    @Bean
    WorkerFactory workerFactory(WorkflowClient workflowClient, Worker worker) {
      WorkerFactory workerFactory = mock(WorkerFactory.class);
      when(workerFactory.newWorker("firemud:automation-scripting-service:script-patch-readiness"))
          .thenReturn(worker);
      return workerFactory;
    }

    @Bean
    TemporalWorkerRegistrar temporalWorkerRegistrar(TemporalTaskQueueResolver taskQueues) {
      return new TemporalWorkerRegistrar() {
        @Override
        public List<String> registerWorkers(
            WorkerFactory workerFactory, WorkflowClient workflowClient) {
          String taskQueue = taskQueues.forWorkflowFamily("script-patch-readiness");
          Worker worker = workerFactory.newWorker(taskQueue);
          worker.registerWorkflowImplementationTypes(HostProbeWorkflowImpl.class);
          return List.of(taskQueue);
        }
      };
    }
  }

  static class HostProbeWorkflowImpl {}
}
