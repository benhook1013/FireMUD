package net.firedevops.firemud.worldmanagement.tenant;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit composition gate for canonical acquisition, lifecycle readback and terminalization.
 * Activation, placement and player admission remain separate, denied boundaries.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.world.canonical-first-admission",
    name = "transport-enabled",
    havingValue = "true")
public class WorldCanonicalInitialAdmissionTransportConfiguration {
  @Bean
  public WorldCanonicalInstanceAssociationRepository canonicalAdmissionAssociationRepository(
      DSLContext dsl, Environment environment) {
    WorldAuthoredSourceIntakeConfiguration.requireSecureServer(environment);
    return new WorldCanonicalInstanceAssociationRepository(
        dsl,
        new WorldCompleteLaunchBindingRepository(dsl),
        new WorldAuthoredSourceIntakeRepository(dsl),
        new WorldAuthoredVersionIdentityRepository(dsl));
  }

  @Bean
  public WorldCanonicalInstanceLifecycleReadRepository canonicalAdmissionLifecycleRepository(
      DSLContext dsl,
      PlatformTransactionManager manager,
      WorldCanonicalInstanceAssociationRepository association) {
    return new WorldCanonicalInstanceLifecycleReadRepository(dsl, manager, association);
  }

  @Bean
  public WorldCanonicalInitialAdmissionHoldRepository canonicalAdmissionHoldRepository(
      DSLContext dsl,
      PlatformTransactionManager manager,
      WorldCanonicalInstanceAssociationRepository association,
      WorldCanonicalInstanceLifecycleReadRepository lifecycle) {
    return new WorldCanonicalInitialAdmissionHoldRepository(dsl, manager, association, lifecycle);
  }

  @Bean
  public WorldCanonicalInitialAdmissionHoldFinalizationRepository
      canonicalAdmissionFinalizationRepository(
          DSLContext dsl,
          PlatformTransactionManager manager,
          WorldCanonicalInstanceAssociationRepository association,
          WorldCanonicalInstanceLifecycleReadRepository lifecycle) {
    return new WorldCanonicalInitialAdmissionHoldFinalizationRepository(
        dsl, manager, association, lifecycle);
  }

  @Bean
  public WorldCanonicalInitialAdmissionHoldFinalizationService
      canonicalAdmissionFinalizationService(
          WorldCanonicalInitialAdmissionHoldFinalizationRepository repository,
          ObjectProvider<GameSessionCanonicalInitialAdmissionOwnerClient> client) {
    GameSessionCanonicalInitialAdmissionOwnerClient configured = client.getIfAvailable();
    return new WorldCanonicalInitialAdmissionHoldFinalizationService(
        repository,
        configured == null
            ? WorldCanonicalInitialAdmissionHoldFinalizationService.denyAllVerifier()
            : configured);
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "firemud.world.canonical-first-admission",
      name = "owner-proof-client-enabled",
      havingValue = "true")
  public GameSessionCanonicalInitialAdmissionOwnerClient canonicalAdmissionOwnerClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionCanonicalInitialAdmissionOwnerClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }
}
