package net.firedevops.firemud.gamesession.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.gamesession.service.AccountPublicJwksReadClient;
import net.firedevops.firemud.gamesession.service.GameSessionAccountPublicJwksSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default-off public-key composition only; does not register LOGIN or grant gameplay authority. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.game-session.account-public-jwks",
    name = "enabled",
    havingValue = "true")
public class GameSessionAccountPublicJwksConfiguration {
  @Bean
  AccountPublicJwksReadClient accountPublicJwksReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountPublicJwksReadClient(endpoints, tls, channels, namespace);
  }

  @Bean
  GameSessionAccountPublicJwksSource gameSessionAccountPublicJwksSource(
      AccountPublicJwksReadClient client,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new GameSessionAccountPublicJwksSource(client, namespace);
  }

  @Bean
  AccountPublicJwksCache accountPublicJwksCache(
      GameSessionAccountPublicJwksSource source,
      @Value("${firemud.game-session.account-public-jwks.maximum-cache-age-millis:0}")
          long maximumAge) {
    Duration age = requiredMaximumAge(maximumAge);
    var cache = new AccountPublicJwksCache(source, source.sourceIdentity(), Clock.systemUTC(), age);
    try {
      cache.refresh();
    } catch (AccountPublicJwksCache.SourcePreparedDenyException denied) {
      // The verified source and cache already retain the deny. Start empty so the existing
      // lifecycle task can later consume exact committed and protected-mount recovery proof.
    }
    return cache;
  }

  @Bean
  AccountAsymmetricJwtVerifier accountAsymmetricJwtVerifier(AccountPublicJwksCache cache) {
    return new AccountAsymmetricJwtVerifier(cache, Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  PublicJwksRefresh accountPublicJwksRefresh(
      AccountPublicJwksCache cache,
      @Value("${firemud.game-session.account-public-jwks.maximum-cache-age-millis:0}")
          long maximumAge) {
    return new PublicJwksRefresh(cache, requiredMaximumAge(maximumAge));
  }

  static Duration requiredMaximumAge(long millis) {
    if (millis < 2_000L || millis > 300_000L) {
      throw new IllegalArgumentException("Explicit bounded Account public JWKS cache age required");
    }
    return Duration.ofMillis(millis);
  }

  /** Owns only this opt-in's bounded public-key refresh, never generic runtime scheduling. */
  static final class PublicJwksRefresh implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PublicJwksRefresh.class);
    private final ScheduledExecutorService executor;
    private final AtomicBoolean terminalRejectionReported = new AtomicBoolean();

    PublicJwksRefresh(AccountPublicJwksCache cache, Duration maximumAge) {
      this(
          cache,
          maximumAge,
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "game-session-account-public-jwks-refresh");
                thread.setDaemon(true);
                return thread;
              }));
    }

    PublicJwksRefresh(
        AccountPublicJwksCache cache, Duration maximumAge, ScheduledExecutorService executor) {
      Objects.requireNonNull(cache);
      this.executor = Objects.requireNonNull(executor);
      long delayMillis = requiredMaximumAge(maximumAge.toMillis()).toMillis() / 2L;
      executor.scheduleWithFixedDelay(
          () -> {
            try {
              cache.refresh();
            } catch (AccountPublicJwksCache.SourcePreparedDenyException denied) {
              LOG.warn("Account public JWKS refresh denied: PREPARED_DENY");
            } catch (SourceUnavailableException unavailable) {
              LOG.warn("Account public JWKS refresh unavailable: SOURCE_UNAVAILABLE");
            } catch (InvalidJwksException rejected) {
              if (terminalRejectionReported.compareAndSet(false, true)) {
                LOG.error(
                    "Account public JWKS cache refused until explicit requalification: SOURCE_REJECTED");
              }
            }
          },
          delayMillis,
          delayMillis,
          TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }
}
