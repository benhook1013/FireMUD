package net.firedevops.firemud.gamesession.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.gamesession.data.PreV1CanonicalPlayBootstrapDriver;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayManualFactory;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayStandIns;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Pre-v1 gate waiver (ADR 0185): composes the canonical PLAY stand-ins (store bootstrap, issuer
 * state, INITIAL attestor, region incarnation, budgets) over the LOGIN Coordination Redis. Needs
 * {@value #PROPERTY} and the gate waivers; the prod profile is refused. Nothing is wired into PLAY
 * yet.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${firemud.game-session.canonical-play.pre-v1.enabled:false}"
        + " and ${firemud.pre-v1.gate-waivers.enabled:false}")
public class PreV1CanonicalPlayConfiguration {
  static final String PROPERTY = "firemud.game-session.canonical-play.pre-v1.enabled";

  @Bean(destroyMethod = "shutdown")
  RedisClient preV1CanonicalPlayCoordinationRedis(
      Environment environment,
      @Value("${firemud.game-session.initial-gameplay-login.protected-sources.coordination.uri:}")
          String configuredUri,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.command-timeout-millis:0}")
          int commandTimeoutMillis) {
    requireWaivers(environment);
    RedisClient client =
        RedisClient.create(
            InitialGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(
                configuredUri, commandTimeoutMillis, true));
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  @Bean
  PreV1CanonicalPlayStandIns preV1CanonicalPlayStandIns(
      Environment environment,
      DSLContext dsl,
      @Qualifier("preV1CanonicalPlayCoordinationRedis") RedisClient coordination,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return PreV1CanonicalPlayManualFactory.create(
        requireWaivers(environment), dsl, coordination, workloadNamespace);
  }

  @Bean
  PreV1CanonicalPlayBootstrapDriver preV1CanonicalPlayBootstrapDriver(
      PreV1CanonicalPlayStandIns standIns) {
    return new PreV1CanonicalPlayBootstrapDriver(standIns);
  }

  /** Re-reads the switch so the prod profile is refused even if the property was forced on. */
  static PreV1GateWaivers requireWaivers(Environment environment) {
    PreV1GateWaivers waivers = PreV1GateWaivers.from(environment);
    if (!waivers.enabled()) {
      throw new IllegalStateException(PROPERTY + " requires the pre-v1 gate waivers");
    }
    return waivers;
  }
}
