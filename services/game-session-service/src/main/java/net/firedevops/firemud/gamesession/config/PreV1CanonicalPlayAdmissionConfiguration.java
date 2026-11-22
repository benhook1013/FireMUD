package net.firedevops.firemud.gamesession.config;

import java.time.Clock;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.client.AccountPlayerAdmissionEntitlementClient;
import net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient;
import net.firedevops.firemud.gamesession.data.PreV1CanonicalPlayBootstrapDriver;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayAdmissionOwner;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayStandIns;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Enables the admission composition only on the run-owned pre-v1 fixture deployment. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${firemud.game-session.canonical-play.pre-v1.enabled:false}"
        + " and ${firemud.pre-v1.gate-waivers.enabled:false}"
        + " and ${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}")
public class PreV1CanonicalPlayAdmissionConfiguration {
  @Bean(initMethod = "init", destroyMethod = "close")
  AccountGameplayAdmissionLeaseClient preV1PlayAccountLeaseClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountGameplayAdmissionLeaseClient(endpoints, tls, channels, namespace);
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  AccountPlayerAdmissionEntitlementClient preV1PlayAccountEntitlements(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountPlayerAdmissionEntitlementClient(
        endpoints, tls, channels, namespace, Clock.systemUTC());
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  AccountTenantCapacityClient preV1PlayAccountCapacity(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountTenantCapacityClient(endpoints, tls, channels, namespace, Clock.systemUTC());
  }

  @Bean
  PreV1CanonicalPlayAdmissionOwner preV1CanonicalPlayAdmissionOwner(
      Environment environment,
      DSLContext dsl,
      PreV1CanonicalPlayStandIns standIns,
      @Value("${firemud.game-session.initial-gameplay-login.protected-sources.coordination.uri:}")
          String redisUri,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.command-timeout-millis:0}")
          int redisTimeout,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      @Qualifier("canonicalAdmissionWorldLifecycleClient")
          WorldCanonicalInstanceLifecycleClient world,
      RuntimeIdentity runtimeIdentity,
      RunOwnedCanonicalLaunchPreparationOwner fixture,
      PreV1CanonicalPlayBootstrapDriver bootstrap,
      AccountPlayerAdmissionEntitlementClient entitlements,
      AccountGameplayAdmissionLeaseClient leases,
      AccountTenantCapacityClient capacity,
      AccountPublicJwksCache jwks) {
    PreV1CanonicalPlayConfiguration.requireWaivers(environment);
    return new PreV1CanonicalPlayAdmissionOwner(
        dsl,
        standIns,
        InitialGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(
            redisUri, redisTimeout, true),
        launches,
        world,
        runtimeIdentity,
        fixture,
        bootstrap,
        entitlements,
        leases,
        capacity,
        jwks,
        Clock.systemUTC());
  }
}
