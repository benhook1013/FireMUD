package net.firedevops.firemud.worldmanagement.config;

import java.io.IOException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldOwner;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the canonical initial-admission hold RPC for the run-owned Compose fixture, so Game
 * Session can hold the fixture World instance before its first OPEN. Human holds keep their
 * existing unregistered composition.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
public class RunOwnedFixtureInitialAdmissionHoldConfiguration {

  @Bean(destroyMethod = "close")
  GrpcGameSessionCanonicalInitialAdmissionOwnerClient runOwnedFixtureGameSessionOwnerClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String namespace)
      throws IOException {
    var client =
        new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
            endpoints, tlsProperties, channelFactory, namespace);
    client.init();
    return client;
  }

  @Bean
  WorldCanonicalInstanceAssociationRepository runOwnedFixtureInstanceAssociationRepository(
      DSLContext dsl) {
    return new WorldCanonicalInstanceAssociationRepository(
        dsl,
        new WorldCompleteLaunchBindingRepository(dsl),
        new WorldAuthoredSourceIntakeRepository(dsl),
        new WorldAuthoredVersionIdentityRepository(dsl));
  }

  @Bean
  WorldCanonicalInstanceLifecycleReadRepository runOwnedFixtureInstanceLifecycleReadRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associations) {
    return new WorldCanonicalInstanceLifecycleReadRepository(dsl, transactionManager, associations);
  }

  /** Game Session's JOIN resolution reads the exact canonical instance lifecycle here. */
  @Bean
  WorldCanonicalInstanceLifecycleReadGrpcService runOwnedFixtureInstanceLifecycleReadGrpcService(
      WorldCanonicalInstanceLifecycleReadRepository lifecycle,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new WorldCanonicalInstanceLifecycleReadGrpcService(lifecycle, namespace);
  }

  @Bean
  WorldCanonicalInitialAdmissionHoldGrpcService runOwnedFixtureInitialAdmissionHoldGrpcService(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associations,
      WorldCanonicalInstanceLifecycleReadRepository lifecycle,
      GrpcGameSessionCanonicalInitialAdmissionOwnerClient gameSessionOwner,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    var holds =
        new WorldCanonicalInitialAdmissionHoldRepository(
            dsl, transactionManager, associations, lifecycle);
    return new WorldCanonicalInitialAdmissionHoldGrpcService(
        new WorldCanonicalInitialAdmissionHoldOwner(holds, gameSessionOwner), namespace);
  }
}
