package net.firedevops.firemud.accountservice.config;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.LettuceAccountCoordinationPinnedConnectionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Selects delivered protected sources; never enrolls a signer or provisions Redis or keys. */
@Import(AccountPublicJwksSourceConfiguration.class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.gameplay-login",
    name = "enabled",
    havingValue = "true")
public class AccountGameplayLoginProtectedSourcesConfiguration {
  static final String COORDINATION_CONNECTIONS = "accountGameplayLoginCoordinationConnections";
  static final String ACKNOWLEDGEMENT_REQUIREMENTS = "accountGameplayLoginAcknowledgements";

  @Bean(name = COORDINATION_CONNECTIONS, destroyMethod = "close")
  LettuceAccountCoordinationPinnedConnectionProvider accountGameplayLoginCoordinationConnections(
      @Value("${firemud.account.gameplay-login.coordination.uri:}") String configuredUri,
      @Value("${firemud.account.gameplay-login.coordination.command-timeout-millis:0}")
          int commandTimeoutMillis) {
    try {
      return new LettuceAccountCoordinationPinnedConnectionProvider(
          requiredCoordinationUri(configuredUri, commandTimeoutMillis));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  @Bean(name = ACKNOWLEDGEMENT_REQUIREMENTS)
  AcknowledgementRequirements accountGameplayLoginAcknowledgements(
      @Value("${firemud.account.gameplay-login.coordination.required-local-aof-count:0}")
          int localAof,
      @Value("${firemud.account.gameplay-login.coordination.required-replica-aof-count:0}")
          int replicaAof,
      @Value("${firemud.account.gameplay-login.coordination.acknowledgement-timeout-millis:0}")
          int timeoutMillis) {
    return new AcknowledgementRequirements(localAof, replicaAof, timeoutMillis);
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
      String principal = AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY;
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
      // In particular, do not retain URI/parser causes containing the delivered password.
      throw unavailable();
    } finally {
      if (password != null) Arrays.fill(password, '\0');
    }
  }

  static Path requiredMount(String value) {
    if (value == null || value.isBlank()) throw unavailable();
    try {
      Path path = Path.of(value);
      if (!path.isAbsolute() || !path.equals(path.normalize())) throw unavailable();
      return path;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Protected Account gameplay LOGIN configuration required");
  }
}
