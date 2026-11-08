package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceCustodyRejectedException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;
import net.firedevops.firemud.gamesession.service.AccountPublicJwksReadClient;
import net.firedevops.firemud.gamesession.service.GameSessionAccountPublicJwksSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GameSessionAccountPublicJwksConfigurationTest {
  private static final String PREFIX = "firemud.game-session.account-public-jwks.";

  @Test
  void disabledByDefaultAndWhenExplicitlyDisabled() {
    for (String enabled : new String[] {"", PREFIX + "enabled=false"}) {
      var runner =
          new ApplicationContextRunner()
              .withUserConfiguration(GameSessionAccountPublicJwksConfiguration.class);
      if (!enabled.isEmpty()) runner = runner.withPropertyValues(enabled);
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AccountPublicJwksReadClient.class);
            assertThat(context).doesNotHaveBean(GameSessionAccountPublicJwksSource.class);
            assertThat(context).doesNotHaveBean(AccountPublicJwksCache.class);
            assertThat(context).doesNotHaveBean(AccountAsymmetricJwtVerifier.class);
            assertThat(context)
                .doesNotHaveBean(GameSessionAccountPublicJwksConfiguration.PublicJwksRefresh.class);
          });
    }
  }

  @Test
  void enablingWithoutDeliveredSourcesFailsComposition() {
    new ApplicationContextRunner()
        .withUserConfiguration(GameSessionAccountPublicJwksConfiguration.class)
        .withPropertyValues(PREFIX + "enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void publicOnlyClientRequiresExplicitEndpointMutualTlsAndNamespace() {
    var config = new GameSessionAccountPublicJwksConfiguration();
    var endpoints = new ServiceEndpointsProperties();
    var tls = new CommonGrpcClientProperties();
    var channels = new GrpcChannelFactory();
    assertThatThrownBy(() -> config.accountPublicJwksReadClient(endpoints, tls, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    endpoints.setAccountService("account-service:6565");
    tls.setCaCert("delivered-ca");
    tls.setCertChain("delivered-certificate");
    tls.setPrivateKey("delivered-private-key");
    assertThat(config.accountPublicJwksReadClient(endpoints, tls, channels, "test")).isNotNull();
    tls.setPlaintext(true);
    assertThatThrownBy(() -> config.accountPublicJwksReadClient(endpoints, tls, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setPlaintext(false);
    assertThatThrownBy(() -> config.accountPublicJwksReadClient(endpoints, tls, channels, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cacheAgeMustBeExplicitFiniteAndAllowBoundedRefreshCadence() {
    for (long invalid : new long[] {-1, 0, 1, 1999, 300001, Long.MAX_VALUE}) {
      assertThatThrownBy(
              () -> GameSessionAccountPublicJwksConfiguration.requiredMaximumAge(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(GameSessionAccountPublicJwksConfiguration.requiredMaximumAge(2000))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(GameSessionAccountPublicJwksConfiguration.requiredMaximumAge(300000))
        .isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void cacheInitializesFromActualSourcePinAndRefreshesBeforeCompositionReturns() {
    var source = mock(GameSessionAccountPublicJwksSource.class);
    SourceIdentity identity =
        new SourceIdentity(
            "test",
            "cluster",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "revision-1",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    when(source.sourceIdentity()).thenReturn(identity);
    when(source.load()).thenThrow(new SourceUnavailableException());
    assertThatThrownBy(
            () ->
                new GameSessionAccountPublicJwksConfiguration()
                    .accountPublicJwksCache(source, 2000))
        .isInstanceOf(SourceUnavailableException.class);
    verify(source).sourceIdentity();
    verify(source).load();
  }

  @Test
  void sharedCacheAndVerifierComposeFromAuthenticatedSnapshotWithoutReadinessMetadata()
      throws Exception {
    // Controlled source only. This is composition proof, not producer or deployment custody.
    var source = mock(GameSessionAccountPublicJwksSource.class);
    SourceIdentity identity =
        new SourceIdentity(
            "test",
            "cluster",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "revision-1",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
    String modulus = unsigned(publicKey.getModulus().toByteArray());
    String exponent = unsigned(publicKey.getPublicExponent().toByteArray());
    byte[] jwks =
        ("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key\","
                + "\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""
                + modulus
                + "\",\"e\":\""
                + exponent
                + "\"}]}")
            .getBytes(StandardCharsets.US_ASCII);
    when(source.sourceIdentity()).thenReturn(identity);
    when(source.load()).thenReturn(new PublicJwksSnapshot(identity, jwks));
    var configuration = new GameSessionAccountPublicJwksConfiguration();

    AccountPublicJwksCache cache = configuration.accountPublicJwksCache(source, 2000);

    assertThat(cache.sourceIdentity()).isEqualTo(identity);
    assertThat(cache.keyFor("test-key")).isEqualTo(publicKey);
    assertThat(configuration.accountAsymmetricJwtVerifier(cache)).isNotNull();
    verify(source).load();
  }

  private static String unsigned(byte[] bytes) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }

  @Test
  void boundedProactiveRefreshSurvivesDenialAndStopsAtContextShutdown() {
    for (long age : new long[] {2000, 300000}) {
      var cache = mock(AccountPublicJwksCache.class);
      var scheduler = mock(ScheduledExecutorService.class);
      var task = ArgumentCaptor.forClass(Runnable.class);
      var refresh =
          new GameSessionAccountPublicJwksConfiguration.PublicJwksRefresh(
              cache, Duration.ofMillis(age), scheduler);
      verify(scheduler)
          .scheduleWithFixedDelay(
              task.capture(), eq(age / 2), eq(age / 2), eq(TimeUnit.MILLISECONDS));
      doThrow(new SourceUnavailableException()).doNothing().when(cache).refresh();
      task.getValue().run();
      task.getValue().run();
      verify(cache, times(2)).refresh();
      refresh.close();
      verify(scheduler).shutdownNow();
    }
  }

  @Test
  void terminalSourceRejectionLatchesCacheAndLogsOneFixedErrorWithoutEvidence() {
    var source = mock(TrustedPublicJwksSource.class);
    when(source.load()).thenThrow(new SourceCustodyRejectedException());
    SourceIdentity identity =
        new SourceIdentity(
            "test",
            "cluster",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "revision-1",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    var cache =
        new AccountPublicJwksCache(source, identity, Clock.systemUTC(), Duration.ofSeconds(2));
    var scheduler = mock(ScheduledExecutorService.class);
    var task = ArgumentCaptor.forClass(Runnable.class);
    Logger logger =
        (Logger)
            LoggerFactory.getLogger(
                GameSessionAccountPublicJwksConfiguration.PublicJwksRefresh.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try (var refresh =
        new GameSessionAccountPublicJwksConfiguration.PublicJwksRefresh(
            cache, Duration.ofSeconds(2), scheduler)) {
      verify(scheduler)
          .scheduleWithFixedDelay(task.capture(), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));
      task.getValue().run();
      task.getValue().run();
      verify(source).load();
      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage())
          .isEqualTo(
              "Account public JWKS cache refused until explicit requalification: SOURCE_REJECTED");
      assertThat(event.getArgumentArray()).isNullOrEmpty();
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    verify(scheduler).shutdownNow();
  }

  @Test
  void transientSourceUnavailabilityLogsSeparatelyAndAllowsNextRefresh() {
    var cache = mock(AccountPublicJwksCache.class);
    doThrow(new SourceUnavailableException()).doNothing().when(cache).refresh();
    var scheduler = mock(ScheduledExecutorService.class);
    var task = ArgumentCaptor.forClass(Runnable.class);
    Logger logger =
        (Logger)
            LoggerFactory.getLogger(
                GameSessionAccountPublicJwksConfiguration.PublicJwksRefresh.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try (var refresh =
        new GameSessionAccountPublicJwksConfiguration.PublicJwksRefresh(
            cache, Duration.ofSeconds(2), scheduler)) {
      verify(scheduler)
          .scheduleWithFixedDelay(task.capture(), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));
      task.getValue().run();
      task.getValue().run();
      verify(cache, times(2)).refresh();
      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .isEqualTo("Account public JWKS refresh unavailable: SOURCE_UNAVAILABLE");
      assertThat(event.getArgumentArray()).isNullOrEmpty();
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    verify(scheduler).shutdownNow();
  }
}
