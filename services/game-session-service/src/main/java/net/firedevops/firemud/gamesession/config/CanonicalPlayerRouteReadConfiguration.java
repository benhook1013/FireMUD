package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default-off composition for exact canonical route reads. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.canonical-gameplay-roster-owner-read",
    name = "enabled",
    havingValue = "true")
public class CanonicalPlayerRouteReadConfiguration {
  @Bean(initMethod = "init", destroyMethod = "close")
  GameDesignPublishedRealmPolicyClient canonicalPlayerRoutePolicyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameDesignPublishedRealmPolicyClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  CanonicalPlayerRouteReadService canonicalPlayerRouteReadService(
      ObjectProvider<GameSessionCanonicalRealmCatalogRepository> catalogRepositoryProvider,
      ObjectProvider<CanonicalInitialAdmissionRepository> admissionRepositoryProvider,
      ObjectProvider<CanonicalGameInstanceLaunchAssociationRepository> launchRepositoryProvider,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        catalogRepositoryProvider.getIfAvailable();
    CanonicalInitialAdmissionRepository admissionRepository =
        admissionRepositoryProvider.getIfAvailable();
    CanonicalGameInstanceLaunchAssociationRepository launchRepository =
        launchRepositoryProvider.getIfAvailable();
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || catalogRepository == null
        || admissionRepository == null
        || launchRepository == null) {
      return CanonicalPlayerRouteReadService.unavailable();
    }
    return new CanonicalPlayerRouteReadService(
        workloadNamespace, catalogRepository, admissionRepository, launchRepository);
  }

  @Bean
  CanonicalPublishedPlayerRouteReadService canonicalPublishedPlayerRouteReadService(
      CanonicalPlayerRouteReadService routeReader,
      ObjectProvider<GameSessionCanonicalRealmCatalogRepository> catalogRepositoryProvider,
      ObjectProvider<CanonicalGameInstanceLaunchAssociationRepository> launchRepositoryProvider,
      GameDesignPublishedRealmPolicyClient policyClient,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        catalogRepositoryProvider.getIfAvailable();
    CanonicalGameInstanceLaunchAssociationRepository launchRepository =
        launchRepositoryProvider.getIfAvailable();
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || catalogRepository == null
        || launchRepository == null) {
      return CanonicalPublishedPlayerRouteReadService.unavailable();
    }
    return new CanonicalPublishedPlayerRouteReadService(
        workloadNamespace, routeReader, catalogRepository, launchRepository, policyClient);
  }
}
