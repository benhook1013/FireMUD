package unit.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.config.AccountCanonicalGameplayLoginConfiguration;
import net.firedevops.firemud.accountservice.config.AccountGameplayCoordinationRedisBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

class AccountCanonicalGameplayLoginConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              AccountCanonicalGameplayLoginConfiguration.class,
              AccountResponseEnvelopeKeyring.class,
              AccountResponseEnvelopeCryptography.class,
              AccountGameplayDelegationResponseEnvelopeService.class,
              AccountServiceImpl.class,
              LoginOwnerTestDependencies.class);

  @Test
  void configurationIsAbsentWithoutExplicitEnablement() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(AccountGameplayCanonicalLoginOwner.class);
          assertThat(context).hasSingleBean(AccountResponseEnvelopeCryptography.class);
          assertThat(context).hasSingleBean(AccountGameplayDelegationResponseEnvelopeService.class);
          assertThat(context)
              .doesNotHaveBean("accountCanonicalGameplayLoginCoordinationConnectionProvider");
        });
  }

  @Test
  void configurationIsAbsentWhenDigestKeyringPathIsMissing() {
    contextRunner
        .withPropertyValues(
            "firemud.account.gameplay-canonical-login.enabled=true",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path="
                + "/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path="
                + "/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.account.response-envelope.keyring-path="
                + "/run/secrets/firemud/account-response-envelope",
            "firemud.grpc.workload-namespace=test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AccountGameplayCanonicalLoginOwner.class);
              assertThat(context)
                  .doesNotHaveBean("accountCanonicalGameplayLoginCoordinationConnectionProvider");
            });
  }

  @Test
  void configurationIsAbsentWhenResponseKeyringPathIsRelative() {
    contextRunner
        .withPropertyValues(
            "firemud.account.gameplay-canonical-login.enabled=true",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path="
                + "/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path="
                + "/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.account.response-envelope.keyring-path=relative-response-keyring",
            "firemud.account.gameplay-canonical-login.digest-keyring-path="
                + "/run/secrets/firemud/account-gameplay-login-keyring",
            "firemud.grpc.workload-namespace=test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AccountGameplayCanonicalLoginOwner.class);
            });
  }

  @Test
  void completeExplicitConfigurationRegistersOnlyLazyLoginOwnerGraph() {
    contextRunner
        .withPropertyValues(
            "firemud.account.gameplay-canonical-login.enabled=true",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path="
                + "/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path="
                + "/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.account.response-envelope.keyring-path="
                + "/run/secrets/firemud/account-response-envelope",
            "firemud.account.gameplay-canonical-login.digest-keyring-path="
                + "/run/secrets/firemud/account-gameplay-login-keyring",
            "firemud.grpc.workload-namespace=test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var beanFactory = context.getBeanFactory();
              assertThat(beanFactory.containsBeanDefinition("accountGameplayCanonicalLoginOwner"))
                  .isTrue();
              assertThat(context).doesNotHaveBean("accountInitialGameplayLoginIssuanceService");
              assertThat(context).doesNotHaveBean("accountCanonicalGameplayAdmissionService");
              assertThat(
                      beanFactory
                          .getBeanDefinition("accountGameplayCanonicalLoginOwner")
                          .isLazyInit())
                  .isTrue();
              assertThat(
                      beanFactory.containsBeanDefinition(
                          "accountCanonicalGameplayLoginCoordinationConnectionProvider"))
                  .isTrue();
              assertThat(
                      beanFactory
                          .getBeanDefinition(
                              "accountCanonicalGameplayLoginCoordinationConnectionProvider")
                          .isLazyInit())
                  .isTrue();
            });
  }

  @Test
  void missingProtectedCoordinationBindingFailsWhenOwnerIsResolvedNotAtStartup() {
    try (MockedStatic<AccountGameplayCoordinationRedisBinding> binding =
        org.mockito.Mockito.mockStatic(AccountGameplayCoordinationRedisBinding.class)) {
      binding
          .when(AccountGameplayCoordinationRedisBinding::loadProtected)
          .thenThrow(new AccountGameplayCoordinationRedisBinding.BindingRejectedException());
      contextRunner
          .withPropertyValues(
              "firemud.account.gameplay-canonical-login.enabled=true",
              "firemud.account.jwt-jwks-api.enabled=true",
              "firemud.account.jwt-jwks-api.protected-binding-path="
                  + "/etc/firemud/account-jwt-api/binding.json",
              "firemud.account.jwt-signer.materialization.enabled=true",
              "firemud.account.jwt-signer.materialization.protected-binding-path="
                  + "/etc/firemud/account-jwt-materializer/binding.json",
              "firemud.account.response-envelope.keyring-path="
                  + "/run/secrets/firemud/account-response-envelope",
              "firemud.account.gameplay-canonical-login.digest-keyring-path="
                  + "/run/secrets/firemud/account-gameplay-login-keyring",
              "firemud.grpc.workload-namespace=test")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AccountResponseEnvelopeCryptography.class);
                assertThat(context)
                    .hasSingleBean(AccountGameplayDelegationResponseEnvelopeService.class);
                assertThat(context).hasSingleBean(AccountServiceImpl.class);
                binding.verifyNoInteractions();

                assertThatThrownBy(() -> context.getBean(AccountGameplayCanonicalLoginOwner.class))
                    .hasRootCauseInstanceOf(
                        AccountGameplayCoordinationRedisBinding.BindingRejectedException.class);
                binding.verify(AccountGameplayCoordinationRedisBinding::loadProtected);
              });
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class LoginOwnerTestDependencies {
    @Bean
    AccountAuditOutboxRepository accountAuditOutboxRepository() {
      return mock(AccountAuditOutboxRepository.class);
    }

    @Bean
    AccountConnectScopeRepository accountConnectScopeRepository() {
      return mock(AccountConnectScopeRepository.class);
    }

    @Bean
    AccountJoinOperationRepository accountJoinOperationRepository() {
      return mock(AccountJoinOperationRepository.class);
    }

    @Bean
    AccountEmailLoginChallengeRepository accountEmailLoginChallengeRepository() {
      return mock(AccountEmailLoginChallengeRepository.class);
    }

    @Bean
    AccountRealmAccessGrantRepository accountRealmAccessGrantRepository() {
      return mock(AccountRealmAccessGrantRepository.class);
    }

    @Bean
    AccountMapper accountMapper() {
      return mock(AccountMapper.class);
    }

    @Bean
    ProfileRepository profileRepository() {
      return mock(ProfileRepository.class);
    }

    @Bean
    ProfileMapper profileMapper() {
      return mock(ProfileMapper.class);
    }

    @Bean
    PaymentTransactionRepository paymentTransactionRepository() {
      return mock(PaymentTransactionRepository.class);
    }

    @Bean
    SubscriptionRepository subscriptionRepository() {
      return mock(SubscriptionRepository.class);
    }

    @Bean
    ExternalAccountRepository externalAccountRepository() {
      return mock(ExternalAccountRepository.class);
    }

    @Bean
    PasswordResetTokenRepository passwordResetTokenRepository() {
      return mock(PasswordResetTokenRepository.class);
    }

    @Bean
    EmailVerificationTokenRepository emailVerificationTokenRepository() {
      return mock(EmailVerificationTokenRepository.class);
    }

    @Bean
    NotificationService notificationService() {
      return mock(NotificationService.class);
    }

    @Bean
    EmailService emailService() {
      return mock(EmailService.class);
    }

    @Bean
    MailProperties mailProperties() {
      return mock(MailProperties.class);
    }

    @Bean
    AccountTokenProperties accountTokenProperties() {
      return mock(AccountTokenProperties.class);
    }

    @Bean
    JwtAuthProperties jwtAuthProperties() {
      return mock(JwtAuthProperties.class);
    }

    @Bean
    GameSessionClient gameSessionClient() {
      return mock(GameSessionClient.class);
    }

    @Bean
    EntityManagementClient entityManagementClient() {
      return mock(EntityManagementClient.class);
    }

    @Bean
    JwtUtil jwtUtil() {
      return mock(JwtUtil.class);
    }

    @Bean
    SessionService sessionService() {
      return mock(SessionService.class);
    }

    @Bean
    AccountRepository accountRepository() {
      return mock(AccountRepository.class);
    }

    @Bean
    AccountGameplayDelegationIssuanceRepository gameplayIssuanceRepository() {
      return mock(AccountGameplayDelegationIssuanceRepository.class);
    }

    @Bean
    AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopeRepository() {
      return mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    }

    @Bean
    AccountJwtSignerDesiredStateRepository signerDesiredStateRepository() {
      return mock(AccountJwtSignerDesiredStateRepository.class);
    }

    @Bean
    AccountJwtJwksPublicationRepository jwksPublicationRepository() {
      return mock(AccountJwtJwksPublicationRepository.class);
    }

    @Bean
    AccountAuthoritySourceEvidenceRepository authoritySourceEvidenceRepository() {
      return mock(AccountAuthoritySourceEvidenceRepository.class);
    }

    @Bean
    AccountAuthorityGenerationRepository authorityGenerationRepository() {
      return mock(AccountAuthorityGenerationRepository.class);
    }

    @Bean
    AccountTenantMembershipRepository tenantMembershipRepository() {
      return mock(AccountTenantMembershipRepository.class);
    }

    @Bean
    AccountMembershipPairAuthorityRepository membershipPairAuthorityRepository() {
      return mock(AccountMembershipPairAuthorityRepository.class);
    }

    @Bean
    AccountAuthorityOutboxRepository authorityOutboxRepository() {
      return mock(AccountAuthorityOutboxRepository.class);
    }

    @Bean
    AccountTenantAuthorityEventRepository tenantAuthorityEventRepository() {
      return mock(AccountTenantAuthorityEventRepository.class);
    }

    @Bean
    AccountJwtSignerMaterializerTrustBinding materializerTrustBinding() {
      return mock(AccountJwtSignerMaterializerTrustBinding.class);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return mock(PlatformTransactionManager.class);
    }
  }
}
