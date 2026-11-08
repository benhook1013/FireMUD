package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCoordinationQualification;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.transaction.PlatformTransactionManager;

class AuthenticatedGameplayRuntimeConfigurationTest {
  private static final String ENABLED = "firemud.game-session.authenticated-gameplay.enabled=true";

  @Test
  void absentOrFalseSelectorLeavesRuntimeUnregisteredEvenWithAllAuthorities() {
    for (String property :
        new String[] {"", "firemud.game-session.authenticated-gameplay.enabled=false"}) {
      var runner = infrastructure().withUserConfiguration(ScannedComposition.class);
      if (!property.isEmpty()) runner = runner.withPropertyValues(property);
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InitialGameplayLoginCoordinationQualification.class);
            assertThat(context).hasSingleBean(AccountAsymmetricJwtVerifier.class);
            assertThat(context).doesNotHaveBean(CanonicalGameplayLoginAccountClient.class);
            assertThat(context).doesNotHaveBean(InitialGameplayLoginOwner.class);
            assertThat(context).doesNotHaveBean(DirectTextWorldSelectionService.class);
            assertThat(context).doesNotHaveBean(DirectTextAuthenticatedLobbyOwner.class);
          });
    }
  }

  @Test
  void componentScanComposesRealOwnersFromSeparateAuthorities() {
    infrastructure()
        .withUserConfiguration(ScannedComposition.class)
        .withPropertyValues(ENABLED)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CanonicalGameplayLoginAccountClient.class);
              assertThat(context).hasSingleBean(InitialGameplayLoginOwner.class);
              assertThat(context).hasSingleBean(DirectTextWorldSelectionService.class);
              assertThat(context).hasSingleBean(DirectTextAuthenticatedLobbyOwner.class);
              verifyNoInteractions(
                  context.getBean(InitialGameplayLoginRedisBinding.class),
                  context.getBean(InitialGameplayLoginCoordinationQualification.class),
                  context.getBean(AccountAsymmetricJwtVerifier.class),
                  context.getBean(CanonicalPlayerRouteReadService.class),
                  context.getBean(DSLContext.class));
              // Spring invokes this ordinary client's @PostConstruct callback, but composition
              // must make no Account RPC or other client call.
              assertThat(mockingDetails(context.getBean(AccountClient.class)).getInvocations())
                  .extracting(invocation -> invocation.getMethod().getName())
                  .containsExactly("init");
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void composesWithRuntimeRegisteredBeforeOrAfterAuthorityConfiguration(boolean runtimeFirst) {
    var configurations =
        runtimeFirst
            ? new Class<?>[] {
              AuthenticatedGameplayRuntimeConfiguration.class, ZDeliveredAuthorities.class
            }
            : new Class<?>[] {
              ZDeliveredAuthorities.class, AuthenticatedGameplayRuntimeConfiguration.class
            };
    infrastructure()
        .withUserConfiguration(configurations)
        .withPropertyValues(ENABLED)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CanonicalGameplayLoginAccountClient.class);
              assertThat(context).hasSingleBean(InitialGameplayLoginOwner.class);
              assertThat(context).hasSingleBean(DirectTextWorldSelectionService.class);
              assertThat(context).hasSingleBean(DirectTextAuthenticatedLobbyOwner.class);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"redis", "qualification", "verifier", "routes"})
  void enabledRuntimeFailsStartupForEveryMissingAuthority(String missing) {
    infrastructure()
        .withUserConfiguration(ScannedComposition.class)
        .withPropertyValues(ENABLED, "fixture.authority." + missing + "=false")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
            });
  }

  private static ApplicationContextRunner infrastructure() {
    return new ApplicationContextRunner()
        .withSystemProperties("firemud.grpc.tls-reload.enabled=false")
        .withPropertyValues("firemud.grpc.workload-namespace=test")
        .withBean(ServiceEndpointsProperties.class, ServiceEndpointsProperties::new)
        .withBean(
            CommonGrpcClientProperties.class,
            () -> {
              var tls = new CommonGrpcClientProperties();
              tls.setCaCert("controlled-ca");
              tls.setCertChain("controlled-certificate");
              tls.setPrivateKey("controlled-private-key");
              return tls;
            })
        .withBean(GrpcChannelFactory.class, AuthenticatedGameplayRuntimeConfigurationTest::channels)
        .withBean(BlockingGrpcStubCustomizer.class, BlockingGrpcStubCustomizer::noop)
        .withBean(DSLContext.class, () -> mock(DSLContext.class))
        .withBean(
            "transactionManager",
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withBean(AccountClient.class, () -> mock(AccountClient.class))
        .withBean(GameplayWorldCatalog.class, () -> mock(GameplayWorldCatalog.class));
  }

  private static GrpcChannelFactory channels() {
    var factory = mock(GrpcChannelFactory.class);
    try {
      when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(mock(ManagedChannel.class));
    } catch (SSLException impossible) {
      throw new AssertionError(impossible);
    }
    return factory;
  }

  @TestConfiguration(proxyBeanMethods = false)
  @ComponentScan(
      basePackageClasses = AuthenticatedGameplayRuntimeConfiguration.class,
      useDefaultFilters = false,
      includeFilters =
          @Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {
                AuthenticatedGameplayRuntimeConfiguration.class,
                ZDeliveredAuthorities.class
              }))
  static class ScannedComposition {}

  // Controlled authority doubles prove registration only, not external maintenance qualification,
  // source custody, authenticated RPCs or gameplay activation.
  @TestConfiguration(value = "zDeliveredAuthorities", proxyBeanMethods = false)
  static class ZDeliveredAuthorities {
    @Bean
    @ConditionalOnProperty(prefix = "fixture.authority", name = "redis", matchIfMissing = true)
    InitialGameplayLoginRedisBinding redis() {
      return mock(InitialGameplayLoginRedisBinding.class);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "fixture.authority",
        name = "qualification",
        matchIfMissing = true)
    InitialGameplayLoginCoordinationQualification qualification() {
      return mock(InitialGameplayLoginCoordinationQualification.class);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fixture.authority", name = "verifier", matchIfMissing = true)
    AccountAsymmetricJwtVerifier verifier() {
      return mock(AccountAsymmetricJwtVerifier.class);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fixture.authority", name = "routes", matchIfMissing = true)
    CanonicalPlayerRouteReadService routes() {
      return mock(CanonicalPlayerRouteReadService.class);
    }
  }
}
