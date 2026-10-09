package unit.net.firedevops.firemud.loggingadmin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient;
import net.firedevops.firemud.loggingadmin.config.StartSessionOperatorAuthorizationConfiguration;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Wiring proof only: injected test doubles do not establish a live Logging or Account runtime. */
class StartSessionOperatorAuthorizationConfigurationTest {
  private static final String ENABLED =
      "firemud.logging-admin.start-session-operator-authorization.enabled";
  private static final String WORKLOAD_NAMESPACE = "firemud.grpc.workload-namespace";
  private static final String ACCOUNT_ENDPOINT = "firemud.services.account-service";
  private static final String CERT_CHAIN = "firemud.grpc.cert-chain";
  private static final String PRIVATE_KEY = "firemud.grpc.private-key";
  private static final String CA_CERT = "firemud.grpc.ca-cert";
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @Test
  void compositionIsAbsentWhenOptInIsMissingOrFalse() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            StartSessionOperatorAuthorizationConfiguration.class,
            SharedGrpcConfiguration.class,
            ReservationService.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(StartSessionOperatorAuthorizationClient.class);
              assertThat(context).doesNotHaveBean(StartSessionAuthorizationCoordinator.class);
              assertThat(context.getBean(ControlledGrpcChannelFactory.class).calls()).isEmpty();
            });

    new ApplicationContextRunner()
        .withUserConfiguration(
            StartSessionOperatorAuthorizationConfiguration.class,
            SharedGrpcConfiguration.class,
            ReservationService.class)
        .withPropertyValues(ENABLED + "=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(StartSessionOperatorAuthorizationClient.class);
              assertThat(context).doesNotHaveBean(StartSessionAuthorizationCoordinator.class);
              assertThat(context.getBean(ControlledGrpcChannelFactory.class).calls()).isEmpty();
            });
  }

  @Test
  void enabledCompositionRequiresReservationProviderAndFileBackedMtls(@TempDir Path tlsDirectory)
      throws IOException {
    String[] tlsProperties = fileBackedTlsProperties(tlsDirectory);
    withTlsReloadDisabled(
        () ->
            contextRunner()
                .withUserConfiguration(
                    StartSessionOperatorAuthorizationConfiguration.class,
                    SharedGrpcConfiguration.class)
                .withPropertyValues(
                    concat(
                        new String[] {
                          ENABLED + "=true",
                          WORKLOAD_NAMESPACE + "=gameplay",
                          ACCOUNT_ENDPOINT + "=account-service.gameplay.svc:6565"
                        },
                        tlsProperties))
                .run(context -> assertThat(context).hasFailed()));

    withTlsReloadDisabled(
        () ->
            contextRunner()
                .withUserConfiguration(
                    StartSessionOperatorAuthorizationConfiguration.class,
                    SharedGrpcConfiguration.class,
                    ReservationService.class)
                .withPropertyValues(ENABLED + "=true", WORKLOAD_NAMESPACE + "=gameplay")
                .run(context -> assertThat(context).hasFailed()));
  }

  @Test
  void enabledCompositionRequiresCanonicalWorkloadNamespace(@TempDir Path tlsDirectory)
      throws IOException {
    String[] tlsProperties = fileBackedTlsProperties(tlsDirectory);
    withTlsReloadDisabled(
        () ->
            contextRunner()
                .withUserConfiguration(
                    StartSessionOperatorAuthorizationConfiguration.class,
                    SharedGrpcConfiguration.class,
                    ReservationService.class)
                .withPropertyValues(
                    concat(
                        new String[] {
                          ENABLED + "=true", ACCOUNT_ENDPOINT + "=account-service:6565"
                        },
                        tlsProperties))
                .run(context -> assertThat(context).hasFailed()));

    withTlsReloadDisabled(
        () ->
            contextRunner()
                .withUserConfiguration(
                    StartSessionOperatorAuthorizationConfiguration.class,
                    SharedGrpcConfiguration.class,
                    ReservationService.class)
                .withPropertyValues(
                    concat(
                        new String[] {
                          ENABLED + "=true",
                          WORKLOAD_NAMESPACE + "=Gameplay",
                          ACCOUNT_ENDPOINT + "=account-service:6565"
                        },
                        tlsProperties))
                .run(context -> assertThat(context).hasFailed()));
  }

  @Test
  void explicitCompositionUsesManagedClientLifecycleAndCapturedChannelConfiguration(
      @TempDir Path tlsDirectory) throws IOException {
    String[] tlsProperties = fileBackedTlsProperties(tlsDirectory);
    AtomicBoolean channelShutdown = new AtomicBoolean();

    withTlsReloadDisabled(
        () ->
            contextRunner()
                .withUserConfiguration(
                    StartSessionOperatorAuthorizationConfiguration.class,
                    SharedGrpcConfiguration.class,
                    ReservationService.class)
                .withPropertyValues(
                    concat(
                        new String[] {
                          ENABLED + "=true",
                          WORKLOAD_NAMESPACE + "=gameplay",
                          ACCOUNT_ENDPOINT + "=account-service.gameplay.svc:6565"
                        },
                        tlsProperties))
                .run(
                    context -> {
                      assertThat(context).hasNotFailed();
                      assertThat(context)
                          .hasSingleBean(StartSessionOperatorAuthorizationClient.class);
                      assertThat(context).hasSingleBean(StartSessionAuthorizationCoordinator.class);

                      ControlledGrpcChannelFactory channelFactory =
                          context.getBean(ControlledGrpcChannelFactory.class);
                      assertThat(channelFactory.calls()).hasSize(1);
                      ChannelCall call = channelFactory.calls().get(0);
                      assertThat(call.target()).isEqualTo("account-service.gameplay.svc:6565");
                      assertThat(call.defaultPort()).isEqualTo(6565);
                      assertThat(call.keepAlive()).isTrue();
                      assertThat(call.tlsProperties().isPlaintext()).isFalse();
                      assertThat(call.tlsProperties().getCertChain())
                          .isEqualTo(tlsProperties[0].substring((CERT_CHAIN + "=").length()));

                      context.close();
                      channelShutdown.set(channelFactory.channelWasShutdown());
                    }));

    assertThat(channelShutdown.get()).isTrue();
  }

  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withInitializer(
            context ->
                context.getBeanFactory().setConversionService(new ApplicationConversionService()));
  }

  private static String[] fileBackedTlsProperties(Path tlsDirectory) throws IOException {
    Path certChain = tlsDirectory.resolve("client.crt");
    Path privateKey = tlsDirectory.resolve("client.key");
    Path caCert = tlsDirectory.resolve("ca.crt");
    Files.writeString(certChain, "test-only certificate placeholder");
    Files.writeString(privateKey, "test-only private-key placeholder");
    Files.writeString(caCert, "test-only CA placeholder");
    return new String[] {
      CERT_CHAIN + "=" + certChain, PRIVATE_KEY + "=" + privateKey, CA_CERT + "=" + caCert
    };
  }

  private static String[] concat(String[] first, String[] second) {
    String[] result = new String[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
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
  @EnableConfigurationProperties({
    ServiceEndpointsProperties.class,
    CommonGrpcClientProperties.class
  })
  @Import(ControlledChannelConfiguration.class)
  static class SharedGrpcConfiguration {
    @Bean
    GrpcTlsMaterialResolver grpcTlsMaterialResolver() {
      return new GrpcTlsMaterialResolver();
    }

    @Bean
    BlockingGrpcStubCustomizer blockingGrpcStubCustomizer() {
      return BlockingGrpcStubCustomizer.noop();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ControlledChannelConfiguration {
    @Bean
    ControlledGrpcChannelFactory grpcChannelFactory() {
      return new ControlledGrpcChannelFactory();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ReservationService {
    @Bean
    StartSessionPreAuthorizationReservationService
        startSessionPreAuthorizationReservationService() {
      return mock(StartSessionPreAuthorizationReservationService.class);
    }
  }

  private static final class ControlledGrpcChannelFactory extends GrpcChannelFactory {
    private final ManagedChannel channel = mock(ManagedChannel.class);
    private final AtomicBoolean channelWasShutdown = new AtomicBoolean();
    private final List<ChannelCall> calls = new ArrayList<>();

    private ControlledGrpcChannelFactory() {
      when(channel.shutdown())
          .thenAnswer(
              invocation -> {
                channelWasShutdown.set(true);
                return channel;
              });
    }

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive) {
      calls.add(new ChannelCall(target, defaultPort, properties.copy(), keepAlive));
      return channel;
    }

    private List<ChannelCall> calls() {
      return List.copyOf(calls);
    }

    private boolean channelWasShutdown() {
      return channelWasShutdown.get();
    }
  }

  private record ChannelCall(
      String target,
      int defaultPort,
      CommonGrpcClientProperties tlsProperties,
      boolean keepAlive) {}
}
