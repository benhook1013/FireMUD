package net.firedevops.firemud.gamesession.config;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.AcknowledgementPolicy;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.Profile;
import net.firedevops.firemud.gamesession.service.LettuceInitialGameplayLoginConnectionProvider;
import org.springframework.beans.factory.annotation.Value;
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
          int commandTimeoutMillis) {
    try {
      return new LettuceInitialGameplayLoginConnectionProvider(
          requiredCoordinationUri(configuredUri, commandTimeoutMillis));
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
      if (!"rediss".equals(uri.getScheme())
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
          .withSsl(true)
          .withVerifyPeer(true)
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

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Protected Game Session initial LOGIN sources required");
  }
}
