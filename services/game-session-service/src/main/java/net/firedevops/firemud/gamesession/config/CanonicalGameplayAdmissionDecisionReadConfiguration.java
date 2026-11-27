package net.firedevops.firemud.gamesession.config;

import java.io.IOException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortManualFactory;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Account's canonical REALMS discovery and JOIN resolution read over the canonical player
 * route, published realm and launch association owners. The receiver authenticates its exact
 * Account mTLS peer itself; the negative never-admitted read stays unavailable here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${firemud.canonical-gameplay-roster-owner-read.enabled:false}"
        + " and ${firemud.canonical-game-instance-launch-association-owner-read.enabled:false}")
public class CanonicalGameplayAdmissionDecisionReadConfiguration {

  @Bean(destroyMethod = "close")
  WorldCanonicalInstanceLifecycleClient canonicalAdmissionWorldLifecycleClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace)
      throws IOException {
    var client =
        new WorldCanonicalInstanceLifecycleClient(
            endpoints, tlsProperties, channelFactory, workloadNamespace);
    client.init();
    return client;
  }

  @Bean
  CanonicalGameplayAdmissionDecisionReadGrpcService
      canonicalGameplayAdmissionDecisionReadGrpcService(
          DSLContext dsl,
          CanonicalPlayerRouteReadService routeReader,
          CanonicalPublishedPlayerRouteReadService publishedReader,
          CanonicalGameInstanceLaunchAssociationRepository launchReader,
          WorldCanonicalInstanceLifecycleClient world,
          @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return CanonicalGameplayAdmissionAbortManualFactory.createDecisionReadReceiver(
        dsl, workloadNamespace, routeReader, publishedReader, launchReader, world);
  }
}
