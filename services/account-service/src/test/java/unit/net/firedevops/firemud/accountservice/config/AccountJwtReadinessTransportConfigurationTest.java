package unit.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPrerequisiteConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTransportConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessTransportOwner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessValidationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

class AccountJwtReadinessTransportConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              AccountJwtReadinessPrerequisiteConfiguration.class,
              AccountJwtReadinessTransportConfiguration.class);

  @Test
  void readinessTransportIsAbsentWhenTheOptInIsMissingOrProtectedCompositionIsIncomplete() {
    contextRunner.run(
        context -> {
          assertThat(
                  context
                      .getBeanFactory()
                      .containsBeanDefinition("accountJwtReadinessProbeService"))
              .isFalse();
          assertThat(
                  context
                      .getBeanFactory()
                      .containsBeanDefinition("accountJwtReadinessTransportOwner"))
              .isFalse();
        });

    contextRunner
        .withPropertyValues("firemud.account.jwt-readiness.validation.enabled=true")
        .run(
            context -> {
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessProbeService"))
                  .isFalse();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessTransportOwner"))
                  .isFalse();
            });
  }

  @Test
  void probeOwnerCompositionIsIndependentlyDefaultDeniedAndExplicitlyOptIn() {
    new ApplicationContextRunner()
        .withUserConfiguration(AccountJwtReadinessProbeOwnerConfiguration.class)
        .run(
            context -> {
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessProbeOwnerService"))
                  .isFalse();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessProbeOwnerWorkloadGuard"))
                  .isFalse();
            });

    new ApplicationContextRunner()
        .withUserConfiguration(
            AccountJwtReadinessPrerequisiteConfiguration.class,
            AccountJwtReadinessProbeOwnerConfiguration.class)
        .withPropertyValues(
            "firemud.account.jwt-readiness.probe-owner.enabled=true",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path=/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.grpc.workload-namespace=firemud-prod")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessProbeOwnerService"))
                  .isTrue();
              assertThat(
                      context
                          .getBeanFactory()
                          .getBeanDefinition("accountJwtReadinessProbeOwnerService")
                          .isLazyInit())
                  .isTrue();
            });
  }

  @Test
  void completeExplicitReadinessCompositionInstantiatesLazyCallableOwnerGraphWithoutActivation() {
    contextRunner
        .withUserConfiguration(AccountJwtReadinessTestDependencies.class)
        .withPropertyValues(
            "firemud.account.jwt-readiness.validation.enabled=true",
            "firemud.account.jwt-readiness.validation.max-control-ui-tenant-scopes=256",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path=/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.grpc.workload-namespace=firemud-prod")
        .run(
            context -> {
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessProbeService"))
                  .isTrue();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessTransportOwner"))
                  .isTrue();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtValidatorInventorySource"))
                  .isTrue();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessReceiverInvocationPort"))
                  .isTrue();
              assertThat(
                      context
                          .getBeanFactory()
                          .getBeanDefinition("accountJwtReadinessProbeService")
                          .isLazyInit())
                  .isTrue();
              assertThat(
                      context
                          .getBeanFactory()
                          .getBeanDefinition("accountJwtReadinessTransportOwner")
                          .isLazyInit())
                  .isTrue();

              AccountJwtReadinessTestAssertions.assertActualGraphWithoutActivation(context);
            });
  }
}

@Configuration(proxyBeanMethods = false)
@Import(AccountJwtReadinessValidationService.class)
class AccountJwtReadinessTestDependencies {
  private static final AccountPublicJwksCache.SourceIdentity SOURCE_IDENTITY =
      new AccountPublicJwksCache.SourceIdentity(
          "prod",
          "prod-cluster-1",
          "11111111-1111-4111-8111-111111111111",
          "firemud-prod",
          "22222222-2222-4222-8222-222222222222",
          "33333333-3333-4333-8333-333333333333",
          "api-r1",
          "https://kubernetes.example:6443",
          "a".repeat(64));

  @Bean
  AccountJwtReadinessProbeRepository accountJwtReadinessProbeRepository() {
    return mock(AccountJwtReadinessProbeRepository.class);
  }

  @Bean
  AccountJwtReadinessTrustBinding accountJwtReadinessTrustBinding() {
    return mock(AccountJwtReadinessTrustBinding.class);
  }

  @Bean
  AccountJwtSignerMaterializerTrustBinding accountJwtSignerMaterializerTrustBinding() {
    return mock(AccountJwtSignerMaterializerTrustBinding.class);
  }

  @Bean
  @Primary
  AccountJwtJwksTrustedSource readinessTestTrustedJwksSource() {
    AccountJwtJwksTrustedSource source = mock(AccountJwtJwksTrustedSource.class);
    when(source.sourceIdentity()).thenReturn(SOURCE_IDENTITY);
    return source;
  }

  @Bean
  PlatformTransactionManager readinessTestTransactionManager() {
    return mock(PlatformTransactionManager.class);
  }
}

final class AccountJwtReadinessTestAssertions {
  private AccountJwtReadinessTestAssertions() {}

  static void assertActualGraphWithoutActivation(AssertableApplicationContext context) {
    var beanFactory = context.getBeanFactory();
    assertThat(beanFactory.containsSingleton("accountJwtReadinessProbeService")).isFalse();
    assertThat(beanFactory.containsSingleton("accountJwtReadinessTransportOwner")).isFalse();

    assertThat(context.getBean(AccountJwtReadinessValidationService.class))
        .isExactlyInstanceOf(AccountJwtReadinessValidationService.class);
    assertThat(context.getBean(AccountJwtReadinessProbeService.class))
        .isExactlyInstanceOf(AccountJwtReadinessProbeService.class);
    assertThat(context.getBean(AccountJwtReadinessTransportOwner.class))
        .isExactlyInstanceOf(AccountJwtReadinessTransportOwner.class);
    assertThatThrownBy(
            () -> context.getBean(AccountJwtReadinessReceiverInvocationPort.class).invoke(null))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class)
        .hasNoCause();
    assertThatThrownBy(
            () ->
                context.getBean(AccountJwtReadinessReceiverInvocationPort.class).requireAvailable())
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class)
        .hasNoCause();
    assertThat(context.getBean(AccountJwtValidatorInventorySource.class))
        .isExactlyInstanceOf(AccountJwtValidatorInventorySource.class);

    assertThat(beanFactory.containsSingleton("accountJwtReadinessProbeService")).isTrue();
    assertThat(beanFactory.containsSingleton("accountJwtReadinessTransportOwner")).isTrue();
    assertThat(beanFactory.containsSingleton("accountJwtReadinessJwksApiBinding")).isTrue();
    assertThat(beanFactory.containsSingleton("accountJwtReadinessJwksConfigMapClient")).isFalse();
    assertThat(beanFactory.containsSingleton("accountJwtReadinessTrustedJwksSource")).isFalse();
    assertThat(
            beanFactory.containsSingleton(
                "accountCanonicalGameplayLoginCoordinationConnectionProvider"))
        .isFalse();
    assertThat(beanFactory.containsSingleton("accountCanonicalGameplayLoginRedisClient")).isFalse();
    assertThat(beanFactory.containsSingleton("accountCanonicalGameplayLoginSigner")).isFalse();

    AccountJwtReadinessProbeRepository repository =
        context.getBean(AccountJwtReadinessProbeRepository.class);
    AccountJwtReadinessTrustBinding readinessTrustBinding =
        context.getBean(AccountJwtReadinessTrustBinding.class);
    AccountJwtSignerMaterializerTrustBinding signerTrustBinding =
        context.getBean(AccountJwtSignerMaterializerTrustBinding.class);
    AccountJwtJwksTrustedSource trustedJwksSource =
        context.getBean(AccountJwtJwksTrustedSource.class);
    PlatformTransactionManager transactionManager =
        context.getBean(PlatformTransactionManager.class);

    verifyNoInteractions(repository, readinessTrustBinding, signerTrustBinding, transactionManager);
    verify(trustedJwksSource, times(1)).sourceIdentity();
    verify(trustedJwksSource, never()).load();
  }
}
