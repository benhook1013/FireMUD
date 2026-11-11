package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeOwnerClient;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCoordinationQualification;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding;
import net.firedevops.firemud.gamesession.service.LettuceInitialGameplayLoginConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Dedicated Redis composition proof with a controlled client; no live source is contacted. */
class InitialGameplayLoginProtectedSourcesConfigurationTest {
  private static final String PREFIX =
      "firemud.game-session.initial-gameplay-login.protected-sources.";
  private static final String COORDINATION_URI =
      "rediss://gamesession_coord_app:test-only-password@coordination.example.invalid:6380/0";
  private final InitialGameplayLoginProtectedSourcesConfiguration configuration =
      new InitialGameplayLoginProtectedSourcesConfiguration();

  @Test
  void sourcesAreDisabledByDefaultAndWhenExplicitlyDisabled() {
    for (String enabled : new String[] {"", PREFIX + "enabled=false"}) {
      var runner =
          new ApplicationContextRunner()
              .withUserConfiguration(InitialGameplayLoginProtectedSourcesConfiguration.class);
      if (!enabled.isEmpty()) runner = runner.withPropertyValues(enabled);
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AccountAsymmetricJwtVerifier.class);
            assertThat(context).doesNotHaveBean(InitialGameplayLoginRedisBinding.class);
            assertThat(context)
                .doesNotHaveBean(LettuceInitialGameplayLoginConnectionProvider.class);
          });
    }
  }

  @Test
  void enablingWithoutDeliveredCoordinationInputsFailsComposition() {
    new ApplicationContextRunner()
        .withUserConfiguration(InitialGameplayLoginProtectedSourcesConfiguration.class)
        .withPropertyValues(PREFIX + "enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void dedicatedUriRequiresExactTlsPrincipalDatabaseAndFiniteDeadline() {
    RedisURI uri =
        InitialGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(
            COORDINATION_URI, 875);

    assertThat(uri.getHost()).isEqualTo("coordination.example.invalid");
    assertThat(uri.getPort()).isEqualTo(6380);
    assertThat(uri.isSsl()).isTrue();
    assertThat(uri.isVerifyPeer()).isTrue();
    assertThat(uri.getDatabase()).isZero();
    assertThat(uri.getTimeout()).isEqualTo(Duration.ofMillis(875));
    var credentials =
        uri.getCredentialsProvider().resolveCredentials().block(Duration.ofSeconds(1));
    assertThat(credentials).isNotNull();
    assertThat(credentials.getUsername()).isEqualTo(InitialGameplayLoginRedisBinding.PRINCIPAL);
    assertThat(credentials.getPassword()).containsExactly("test-only-password".toCharArray());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "redis://gamesession_coord_app:secret@coordination.example.invalid:6380/0",
        "rediss://default:secret@coordination.example.invalid:6380/0",
        "rediss://account_coord_app:secret@coordination.example.invalid:6380/0",
        "rediss://gamesession_coord_app:@coordination.example.invalid:6380/0",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid/0",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid:6380",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid:6380/1",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid:6380/0?verifyPeer=false",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid:6380/0#alternate",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid:99999/0",
        "rediss://gamesession_coord_app:secret@coordination.example.invalid:6380/0 bad"
      })
  void rejectsAmbientPlaintextChangedOrAmbiguousCoordinationSources(String uri) {
    assertThatThrownBy(
            () ->
                InitialGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(uri, 875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected Game Session initial LOGIN sources required")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 60_001})
  void rejectsUnboundedCommandDeadlines(int timeoutMillis) {
    assertThatThrownBy(
            () ->
                InitialGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(
                    COORDINATION_URI, timeoutMillis))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause();
  }

  @Test
  void sourceBindingRequiresExplicitProfileIdentityAndAcknowledgements() {
    var connections = mock(LettuceInitialGameplayLoginConnectionProvider.class);
    assertThatThrownBy(
            () ->
                configuration.initialGameplayLoginRedisBinding(
                    connections, "", "REPLICATED", 1, 1, 875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected Coordination binding identity required")
        .hasNoCause();
    assertThatThrownBy(
            () ->
                configuration.initialGameplayLoginRedisBinding(
                    connections, "delivered-binding", "", 1, 1, 875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected Coordination profile is invalid")
        .hasNoCause();
    assertThatThrownBy(
            () ->
                configuration.initialGameplayLoginRedisBinding(
                    connections, "delivered-binding", "REPLICATED", 1, 0, 875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Explicit Coordination ACK policy is invalid")
        .hasNoCause();
    assertThatThrownBy(
            () ->
                configuration.initialGameplayLoginRedisBinding(
                    connections, "delivered-binding", "SINGLE_NODE_NO_FAILOVER", 1, 1, 875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Explicit Coordination ACK policy is invalid")
        .hasNoCause();
    verifyNoInteractions(connections);
  }

  @Test
  void invalidBindingDiagnosticsDoNotRetainDeliveredValuesOrCauses() {
    var connections = mock(LettuceInitialGameplayLoginConnectionProvider.class);
    assertThatThrownBy(
            () ->
                configuration.initialGameplayLoginRedisBinding(
                    connections,
                    "delivered-binding",
                    "rediss://user:do-not-log@private.example.invalid:6380/0",
                    1,
                    1,
                    875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected Coordination profile is invalid")
        .hasNoCause();
    assertThatThrownBy(
            () ->
                configuration.initialGameplayLoginRedisBinding(
                    connections, "do-not-log-binding\n", "REPLICATED", 1, 1, 875))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected Coordination binding identity required")
        .hasNoCause();
    verifyNoInteractions(connections);
  }

  @Test
  void concreteRedisSourceDoesNotSupplyVerifierMaintenanceAuthorityOrLogin() {
    var client = mock(RedisClient.class);
    try (var factory = mockStatic(RedisClient.class)) {
      factory.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
      new ApplicationContextRunner()
          .withUserConfiguration(
              InitialGameplayLoginProtectedSourcesConfiguration.class,
              AuthenticatedGameplayRuntimeConfiguration.class)
          .withPropertyValues(completeProperties())
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(GameSessionJwtReadinessProbeOwnerClient.class);
                assertThat(context).doesNotHaveBean(AccountPublicJwksCache.class);
                assertThat(context).doesNotHaveBean(AccountAsymmetricJwtVerifier.class);
                assertThat(context).hasSingleBean(InitialGameplayLoginRedisBinding.class);
                assertThat(context)
                    .doesNotHaveBean(InitialGameplayLoginCoordinationQualification.class);
                assertThat(context).doesNotHaveBean(InitialGameplayLoginOwner.class);
                var binding = context.getBean(InitialGameplayLoginRedisBinding.class);
                assertThat(binding.protectedBindingId()).isEqualTo("delivered-binding");
                assertThat(binding.acknowledgementPolicy().requiredReplicaAofCount()).isEqualTo(1);
              });
      verify(client, times(1)).shutdown();
    }
  }

  private static String[] completeProperties() {
    return new String[] {
      PREFIX + "enabled=true",
      PREFIX + "coordination.uri=" + COORDINATION_URI,
      PREFIX + "coordination.command-timeout-millis=875",
      PREFIX + "coordination.protected-binding-id=delivered-binding",
      PREFIX + "coordination.profile=REPLICATED",
      PREFIX + "coordination.required-local-aof-count=1",
      PREFIX + "coordination.required-replica-aof-count=1",
      PREFIX + "coordination.acknowledgement-timeout-millis=875"
    };
  }
}
