package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionFixtureCoordinator;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService;
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
@org.springframework.context.annotation.Import(CanonicalPlayerRouteReaderConfiguration.class)
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

  @Bean
  CanonicalGameplayRosterSelectionService canonicalGameplayRosterSelectionService(
      CanonicalPlayerRouteReadService routeReader,
      CanonicalPublishedPlayerRouteReadService publishedRouteReader,
      CanonicalGameplayRosterClient rosterClient) {
    return new CanonicalGameplayRosterSelectionService(
        routeReader, publishedRouteReader, rosterClient);
  }
}

/** One route reader composes independently gated Human and fixed-fixture authority arms. */
@Configuration(proxyBeanMethods = false)
class CanonicalPlayerRouteReaderConfiguration {
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
      "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false} or ${firemud.canonical-gameplay-roster-owner-read.enabled:false}")
  CanonicalPlayerRouteReadService canonicalPlayerRouteReadService(
      ObjectProvider<GameSessionCanonicalRealmCatalogRepository> catalogOwners,
      ObjectProvider<CanonicalInitialAdmissionRepository> admissionOwners,
      ObjectProvider<CanonicalGameInstanceLaunchAssociationRepository> launchOwners,
      ObjectProvider<RunOwnedInitialAdmissionFixtureCoordinator> fixtureCoordinators,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}") boolean fixtureEnabled,
      @Value("${firemud.canonical-gameplay-roster-owner-read.enabled:false}") boolean humanEnabled,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    var coordinator = fixtureEnabled ? fixtureCoordinators.getIfAvailable() : null;
    var catalog = humanEnabled ? catalogOwners.getIfAvailable() : null;
    var admission = humanEnabled ? admissionOwners.getIfAvailable() : null;
    var launch = humanEnabled ? launchOwners.getIfAvailable() : null;
    if (!GrpcPeerIdentity.isValidNamespace(namespace)
        || (fixtureEnabled && coordinator == null)
        || (humanEnabled && (catalog == null || admission == null || launch == null))) {
      return CanonicalPlayerRouteReadService.unavailable();
    }
    return new CanonicalPlayerRouteReadService(namespace, catalog, admission, launch,
        fixtureEnabled ? coordinator : null);
  }
}
