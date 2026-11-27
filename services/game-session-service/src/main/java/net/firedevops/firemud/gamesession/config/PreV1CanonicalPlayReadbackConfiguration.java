package net.firedevops.firemud.gamesession.config;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.lettuce.core.RedisClient;
import java.time.Clock;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCapacityExecutionOwner;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionReadRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayTenantGameplayTagRepository;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayManualFactory;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayStandIns;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Account-only original NEW readbacks for the default-off, run-owned pre-v1 PLAY fixture. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${firemud.game-session.canonical-play.pre-v1.enabled:false}"
        + " and ${firemud.pre-v1.gate-waivers.enabled:false}")
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
public class PreV1CanonicalPlayReadbackConfiguration {

  @Bean
  BindableService preV1CanonicalPlayFinalAdmissionReadReceiver(
      Environment environment,
      DSLContext dsl,
      @Qualifier("preV1CanonicalPlayCoordinationRedis") RedisClient coordination,
      RunOwnedCanonicalLaunchPreparationOwner fixture,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    PreV1CanonicalPlayConfiguration.requireWaivers(environment);
    var tags =
        new CanonicalGameplayTenantGameplayTagRepository(
            dsl, namespace, PreV1CanonicalPlayStandIns.TAG_ALLOCATION_VERSION);
    var guard = new CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard(namespace);
    var receiver =
        new CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService(
            new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
                dsl, coordination, tags, namespace, Clock.systemUTC()),
            guard,
            namespace);
    return () ->
        ServerInterceptors.interceptForward(
            receiver,
            new GrpcPeerIdentityInterceptor(),
            guard,
            fixtureCapabilityGuard(environment, fixture));
  }

  @Bean
  BindableService preV1CanonicalPlayCapacityExecutionReadReceiver(
      Environment environment,
      DSLContext dsl,
      @Qualifier("preV1CanonicalPlayCoordinationRedis") RedisClient coordination,
      RunOwnedCanonicalLaunchPreparationOwner fixture,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    PreV1CanonicalPlayConfiguration.requireWaivers(environment);
    var tags =
        new CanonicalGameplayTenantGameplayTagRepository(
            dsl, namespace, PreV1CanonicalPlayStandIns.TAG_ALLOCATION_VERSION);
    var guard = new CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard(namespace);
    var receiver =
        new CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService(
            new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                dsl,
                coordination,
                PreV1CanonicalPlayManualFactory.mergedScriptCatalog(),
                Clock.systemUTC(),
                tags),
            guard,
            namespace);
    return () ->
        ServerInterceptors.interceptForward(
            receiver,
            new GrpcPeerIdentityInterceptor(),
            guard,
            fixtureCapabilityGuard(environment, fixture));
  }

  /**
   * Checks fixed capability/receipt custody only after the exact Account peer has authenticated.
   */
  private static ServerInterceptor fixtureCapabilityGuard(
      Environment environment, RunOwnedCanonicalLaunchPreparationOwner fixture) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        try {
          PreV1CanonicalPlayConfiguration.requireWaivers(environment);
          fixture.loadFixedVerifiedEvidence().requireVerifiedFixture();
        } catch (RuntimeException unavailable) {
          call.close(
              Status.UNAVAILABLE.withDescription("Run-owned canonical PLAY fixture is unavailable"),
              new Metadata());
          return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
      }
    };
  }
}
