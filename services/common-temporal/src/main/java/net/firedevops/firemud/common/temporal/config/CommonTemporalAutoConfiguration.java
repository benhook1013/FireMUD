package net.firedevops.firemud.common.temporal.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.common.temporal.TemporalWorkerRegistrar;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass({WorkflowClient.class, WorkflowServiceStubs.class, WorkerFactory.class})
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnProperty(prefix = "firemud.temporal", name = "enabled", havingValue = "true")
public class CommonTemporalAutoConfiguration {

  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean
  public WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
    WorkflowServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder().setTarget(properties.getTarget()).build();
    return WorkflowServiceStubs.newServiceStubs(options);
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkflowClient workflowClient(
      WorkflowServiceStubs workflowServiceStubs, TemporalProperties properties) {
    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder().setNamespace(properties.getNamespace()).build();
    return WorkflowClient.newInstance(workflowServiceStubs, options);
  }

  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "firemud.temporal",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public WorkerFactory workerFactory(WorkflowClient workflowClient) {
    return WorkerFactory.newInstance(workflowClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public TemporalTaskQueueResolver temporalTaskQueueResolver(
      Environment environment, TemporalProperties properties) {
    return new TemporalTaskQueueResolver(
        environment.getProperty("spring.application.name", "unknown-service"), properties);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "firemud.temporal",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public TemporalWorkerHost temporalWorkerHost(
      WorkerFactory workerFactory,
      WorkflowClient workflowClient,
      ObjectProvider<TemporalWorkerRegistrar> registrars) {
    return new TemporalWorkerHost(
        workerFactory, workflowClient, registrars.orderedStream().toList());
  }
}
