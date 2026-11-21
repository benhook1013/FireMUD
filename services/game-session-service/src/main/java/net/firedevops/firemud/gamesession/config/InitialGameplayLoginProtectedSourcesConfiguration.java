package net.firedevops.firemud.gamesession.config;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCoordinationQualification;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.AcknowledgementPolicy;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.Profile;
import net.firedevops.firemud.gamesession.service.LettuceInitialGameplayLoginConnectionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composes the dedicated Coordination source without granting JWT trust, maintenance qualification
 * or gameplay. Endpoint, credential and deployment custody must be independently delivered.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.game-session.initial-gameplay-login.protected-sources",
    name = "enabled",
    havingValue = "true")
public class InitialGameplayLoginProtectedSourcesConfiguration {
  @Bean(destroyMethod = "close")
  LettuceInitialGameplayLoginConnectionProvider initialGameplayLoginCoordinationConnections(
      @Value("${firemud.game-session.initial-gameplay-login.protected-sources.coordination.uri:}")
          String configuredUri,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.command-timeout-millis:0}")
          int commandTimeoutMillis,
      org.springframework.beans.factory.ObjectProvider<PreV1GateWaivers> waivers) {
    boolean waived = waivers.getIfAvailable(PreV1GateWaivers::disabled).enabled();
    try {
      return new LettuceInitialGameplayLoginConnectionProvider(
          requiredCoordinationUri(configuredUri, commandTimeoutMillis, waived));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  @Bean
  InitialGameplayLoginRedisBinding initialGameplayLoginRedisBinding(
      LettuceInitialGameplayLoginConnectionProvider connections,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.protected-binding-id:}")
          String protectedBindingId,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.profile:}")
          String profile,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.required-local-aof-count:0}")
          int localAof,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.required-replica-aof-count:0}")
          int replicaAof,
      @Value(
              "${firemud.game-session.initial-gameplay-login.protected-sources.coordination.acknowledgement-timeout-millis:0}")
          int timeoutMillis) {
    final Profile selectedProfile;
    try {
      selectedProfile = Profile.valueOf(profile == null ? "" : profile);
    } catch (IllegalArgumentException invalid) {
      // Enum parsing can include a delivered value. Keep only the fixed failure category.
      throw new IllegalArgumentException("Protected Coordination profile is invalid");
    }
    // These owner validators already expose fixed, cause-free identity and ACK diagnostics.
    return new InitialGameplayLoginRedisBinding(
        protectedBindingId,
        connections::openPinnedConnection,
        new AcknowledgementPolicy(selectedProfile, localAof, replicaAof, timeoutMillis));
  }

  static RedisURI requiredCoordinationUri(String configuredUri, int timeoutMillis) {
    return requiredCoordinationUri(configuredUri, timeoutMillis, false);
  }

  /**
   * Requires TLS ({@code rediss}). Under the pre-v1 gate waiver (ADR 0185) Compose smoke may use
   * plaintext {@code redis} with the same ACL principal; TLS is owed before v1.
   */
  static RedisURI requiredCoordinationUri(
      String configuredUri, int timeoutMillis, boolean preV1Plaintext) {
    char[] password = null;
    try {
      if (configuredUri == null
          || configuredUri.length() > 4096
          || timeoutMillis < 1
          || timeoutMillis > 60_000) {
        throw unavailable();
      }
      URI uri = URI.create(configuredUri);
      String userInfo = uri.getUserInfo();
      String principal = InitialGameplayLoginRedisBinding.PRINCIPAL;
      boolean tls = "rediss".equals(uri.getScheme());
      if (!(tls || preV1Plaintext && "redis".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getHost().isBlank()
          || uri.getPort() < 1
          || uri.getPort() > 65_535
          || !"/0".equals(uri.getRawPath())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || userInfo == null
          || !userInfo.startsWith(principal + ":")
          || userInfo.length() == principal.length() + 1
          || userInfo.chars().anyMatch(Character::isISOControl)) {
        throw unavailable();
      }
      password = userInfo.substring(principal.length() + 1).toCharArray();
      var credentials = RedisCredentials.just(principal, password);
      return RedisURI.Builder.redis(uri.getHost(), uri.getPort())
          .withSsl(tls)
          .withVerifyPeer(tls)
          .withAuthentication(RedisCredentialsProvider.from(() -> credentials))
          .withDatabase(0)
          .withTimeout(Duration.ofMillis(timeoutMillis))
          .build();
    } catch (RuntimeException failure) {
      // URI parsing can include the delivered credential in its message or cause.
      throw unavailable();
    } finally {
      if (password != null) Arrays.fill(password, '\0');
    }
  }

  /**
   * The external qualification authority has no provider yet. Under the pre-v1 gate waiver (ADR
   * 0185) a process-local lease stands in so Compose smoke can run LOGIN; the real reset, restore
   * and promotion exclusion is owed before v1. Without the waiver no lease is supplied.
   */
  @Bean
  @ConditionalOnMissingBean(InitialGameplayLoginCoordinationQualification.class)
  @ConditionalOnProperty(name = PreV1GateWaivers.PROPERTY, havingValue = "true")
  InitialGameplayLoginCoordinationQualification preV1InitialGameplayLoginQualification(
      PreV1GateWaivers waivers) {
    if (!waivers.enabled()) {
      throw new IllegalStateException(
          "Initial LOGIN Coordination qualification authority is required");
    }
    AtomicLong fences = new AtomicLong(System.currentTimeMillis());
    return (scope, bindingId) -> {
      long fence = fences.incrementAndGet();
      return Optional.of(
          new InitialGameplayLoginCoordinationQualification.Lease() {
            @Override
            public String scope() {
              return scope;
            }

            @Override
            public String protectedBindingId() {
              return bindingId;
            }

            @Override
            public long fence() {
              return fence;
            }

            @Override
            public void requireCurrent() {}

            @Override
            public void close() {}
          });
    };
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Protected Game Session initial LOGIN sources required");
  }
}
