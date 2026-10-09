package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannel;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.StartSessionOperatorRedemptionClient;
import net.firedevops.firemud.gamesession.config.GameSessionStartSessionOperatorAuthorizationConfiguration;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.service.GameSessionStartSessionOperatorAuthorizationCoordinator;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

class GameSessionStartSessionOperatorAuthorizationConfigurationTest {
  private static final String OWNER_OPT_IN = "firemud.start-session-operator-authorization.enabled";
  private static final String OWNER_CLAIM_LEASE =
      "firemud.start-session-operator-authorization.owner-claim-lease";
  private static final String WORKLOAD_NAMESPACE = "firemud.grpc.workload-namespace";
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @Test
  void ownerAuthorizationCompositionIsDisabledByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(GameSessionStartSessionOperatorAuthorizationConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(GameSessionStartSessionOperatorAttemptRepository.class);
              assertThat(context).doesNotHaveBean(StartSessionOperatorRedemptionClient.class);
              assertThat(context)
                  .doesNotHaveBean(GameSessionStartSessionOperatorAuthorizationCoordinator.class);
            });
  }

  @Test
  void enabledCompositionRequiresExplicitOwnerLeaseAndWorkloadNamespace() {
    withTlsReloadDisabled(
        () ->
            new ApplicationContextRunner()
                .withUserConfiguration(
                    GameSessionStartSessionOperatorAuthorizationConfiguration.class,
                    Dependencies.class)
                .withPropertyValues(
                    OWNER_OPT_IN + "=true",
                    WORKLOAD_NAMESPACE + "=gameplay",
                    "firemud.grpc.cert-chain=test-cert",
                    "firemud.grpc.private-key=test-key",
                    "firemud.grpc.ca-cert=test-ca")
                .run(context -> assertThat(context).hasFailed()));

    withTlsReloadDisabled(
        () ->
            new ApplicationContextRunner()
                .withUserConfiguration(
                    GameSessionStartSessionOperatorAuthorizationConfiguration.class,
                    Dependencies.class)
                .withPropertyValues(
                    OWNER_OPT_IN + "=true",
                    OWNER_CLAIM_LEASE + "=30s",
                    "firemud.grpc.cert-chain=test-cert",
                    "firemud.grpc.private-key=test-key",
                    "firemud.grpc.ca-cert=test-ca")
                .run(context -> assertThat(context).hasFailed()));
  }

  @Test
  void enabledCompositionRequiresMutualTlsPropertiesAndOwnerCollaborators() {
    withTlsReloadDisabled(
        () ->
            new ApplicationContextRunner()
                .withUserConfiguration(
                    GameSessionStartSessionOperatorAuthorizationConfiguration.class,
                    Dependencies.class)
                .withPropertyValues(
                    OWNER_OPT_IN + "=true",
                    OWNER_CLAIM_LEASE + "=30s",
                    WORKLOAD_NAMESPACE + "=gameplay")
                .run(context -> assertThat(context).hasFailed()));

    withTlsReloadDisabled(
        () ->
            new ApplicationContextRunner()
                .withUserConfiguration(
                    GameSessionStartSessionOperatorAuthorizationConfiguration.class)
                .withPropertyValues(completeOwnerProperties())
                .run(context -> assertThat(context).hasFailed()));
  }

  @Test
  void ownerClaimLeaseUsesRepositoryFiveMinuteBound() {
    withTlsReloadDisabled(
        () ->
            new ApplicationContextRunner()
                .withUserConfiguration(
                    GameSessionStartSessionOperatorAuthorizationConfiguration.class,
                    Dependencies.class)
                .withPropertyValues(
                    OWNER_OPT_IN + "=true",
                    OWNER_CLAIM_LEASE + "=6m",
                    WORKLOAD_NAMESPACE + "=gameplay",
                    "firemud.grpc.cert-chain=test-cert",
                    "firemud.grpc.private-key=test-key",
                    "firemud.grpc.ca-cert=test-ca")
                .run(context -> assertThat(context).hasFailed()));
  }

  @Test
  void explicitCompositionUsesOwnerDependenciesAndManagedClientLifecycle() {
    withTlsReloadDisabled(
        () ->
            new ApplicationContextRunner()
                .withUserConfiguration(
                    GameSessionStartSessionOperatorAuthorizationConfiguration.class,
                    Dependencies.class)
                .withPropertyValues(completeOwnerProperties())
                .run(
                    context -> {
                      assertThat(context).hasNotFailed();
                      assertThat(context)
                          .hasSingleBean(GameSessionStartSessionOperatorAttemptRepository.class);
                      assertThat(context).hasSingleBean(StartSessionOperatorRedemptionClient.class);
                      assertThat(context)
                          .hasSingleBean(
                              GameSessionStartSessionOperatorAuthorizationCoordinator.class);

                      var channelFactory = context.getBean(ControlledGrpcChannelFactory.class);
                      assertThat(channelFactory.buildCount()).isEqualTo(1);
                      assertThat(channelFactory.lastTarget()).isEqualTo("account-service:6565");
                      assertThat(channelFactory.lastDefaultPort()).isEqualTo(6565);
                      assertThat(channelFactory.lastKeepAlive()).isTrue();
                      context.close();
                      verify(channelFactory.channel()).shutdown();
                    }));
  }

  private static String[] completeOwnerProperties() {
    return new String[] {
      OWNER_OPT_IN + "=true",
      OWNER_CLAIM_LEASE + "=30s",
      WORKLOAD_NAMESPACE + "=gameplay",
      "firemud.grpc.cert-chain=test-cert",
      "firemud.grpc.private-key=test-key",
      "firemud.grpc.ca-cert=test-ca"
    };
  }

  private static void withTlsReloadDisabled(Runnable action) {
    String previous = System.getProperty(TLS_RELOAD_PROPERTY);
    System.setProperty(TLS_RELOAD_PROPERTY, "false");
    try {
      action.run();
    } finally {
      if (previous == null) {
        System.clearProperty(TLS_RELOAD_PROPERTY);
      } else {
        System.setProperty(TLS_RELOAD_PROPERTY, previous);
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean(name = "conversionService")
    ConversionService applicationConversionService() {
      return ApplicationConversionService.getSharedInstance();
    }

    @Bean
    DSLContext startSessionOwnerDsl() {
      return mock(DSLContext.class);
    }

    @Bean
    PlatformTransactionManager startSessionOwnerTransactionManager() {
      return mock(PlatformTransactionManager.class);
    }

    @Bean
    ServiceEndpointsProperties startSessionServiceEndpoints() {
      return new ServiceEndpointsProperties();
    }

    @Bean
    CommonGrpcClientProperties startSessionGrpcClientProperties(Environment environment) {
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(environment.getProperty("firemud.grpc.cert-chain"));
      properties.setPrivateKey(environment.getProperty("firemud.grpc.private-key"));
      properties.setCaCert(environment.getProperty("firemud.grpc.ca-cert"));
      return properties;
    }

    @Bean
    ControlledGrpcChannelFactory startSessionGrpcChannelFactory() {
      return new ControlledGrpcChannelFactory();
    }

    @Bean
    BlockingGrpcStubCustomizer startSessionGrpcStubCustomizer() {
      return BlockingGrpcStubCustomizer.noop();
    }
  }

  static class ControlledGrpcChannelFactory extends GrpcChannelFactory {
    private final ManagedChannel channel = mock(ManagedChannel.class);
    private int buildCount;
    private String lastTarget;
    private int lastDefaultPort;
    private boolean lastKeepAlive;

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive) {
      buildCount++;
      lastTarget = target;
      lastDefaultPort = defaultPort;
      lastKeepAlive = keepAlive;
      return channel;
    }

    int buildCount() {
      return buildCount;
    }

    String lastTarget() {
      return lastTarget;
    }

    int lastDefaultPort() {
      return lastDefaultPort;
    }

    boolean lastKeepAlive() {
      return lastKeepAlive;
    }

    ManagedChannel channel() {
      return channel;
    }
  }
}
