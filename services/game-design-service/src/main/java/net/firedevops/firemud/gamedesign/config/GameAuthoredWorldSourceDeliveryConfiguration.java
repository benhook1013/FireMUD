package net.firedevops.firemud.gamedesign.config;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryService;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/** Owner-local registration for durable delivery; nothing is scheduled or dispatched by default. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.authored-world-source",
    name = "enabled",
    havingValue = "true")
@EnableScheduling
public class GameAuthoredWorldSourceDeliveryConfiguration {
  @Bean(initMethod = "init", destroyMethod = "close")
  public WorldAuthoredSourceIntakeClient worldAuthoredSourceIntakeClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new WorldAuthoredSourceIntakeClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  public GameAuthoredWorldSourceDeliveryService gameAuthoredWorldSourceDeliveryService(
      GameAuthoredWorldSourceDeliveryRepository repository,
      WorldAuthoredSourceIntakeClient intakeClient,
      PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameAuthoredWorldSourceDeliveryService(
        repository, intakeClient, transactionManager, workloadNamespace);
  }

  @Bean
  public GameAuthoredWorldSourceDeliveryWorker gameAuthoredWorldSourceDeliveryWorker(
      GameAuthoredWorldSourceDeliveryRepository repository,
      GameAuthoredWorldSourceDeliveryService deliveryService,
      GameAuthoredWorldSourceDeliveryProperties properties,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameAuthoredWorldSourceDeliveryWorker(
        repository, deliveryService, properties, workloadNamespace);
  }
}
