package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisContract;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAuthenticateReceiver;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Real intermediate-owner wiring with controlled SQL, primary credentials, API trust and Redis
 * transport edges. This does not prove protected key custody, enrolled signer state or LOGIN.
 */
class AccountGameplayLoginSourceConfigurationTest {
  private static final Class<?>[] CONFIGURATIONS = {
    CanonicalGameplayRuntimeConfiguration.class,
    AccountGameplayLoginSourceConfiguration.class,
    AccountGameplayLoginProtectedSourcesConfiguration.class
  };

  @Test
  void entireGraphIsAbsentByDefaultEvenWithoutOwnerInfrastructure() {
    new ApplicationContextRunner()
        .withUserConfiguration(CONFIGURATIONS)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AccountGameplayAuthenticateReceiver.class);
              assertThat(context).doesNotHaveBean(AccountGameplayDelegationSigner.class);
              assertThat(context).doesNotHaveBean(AccountJwtJwksApiBinding.class);
              assertThat(context).doesNotHaveBean(AccountGameplayDelegationRedisClient.class);
            });
    new ApplicationContextRunner()
        .withUserConfiguration(CONFIGURATIONS)
        .withPropertyValues("firemud.account.gameplay-login.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AccountGameplayAuthenticateReceiver.class);
            });
  }

  @Test
  void explicitEnableCannotSilentlyLeaveTheReceiverUncomposed() {
    new ApplicationContextRunner()
        .withUserConfiguration(CONFIGURATIONS)
        .withPropertyValues("firemud.account.gameplay-login.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void fullCompositionUsesGenuineIntermediateOwnersAndClosesItsDedicatedClient() {
    RedisClient client = mock(RedisClient.class);
    try (var redisFactory = mockStatic(RedisClient.class);
        var apiConstruction =
            mockConstruction(
                AccountJwtJwksApiBinding.class,
                (api, construction) ->
                    when(api.current())
                        .thenReturn(
                            AccountGameplayLoginProtectedSourcesConfigurationTest.apiBinding(
                                "test", "cluster-a")))) {
      redisFactory.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
      enabledContext()
          .withPropertyValues("firemud.account.public-jwks-read.enabled=true")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                for (Class<?> owner :
                    List.of(
                        AccountAuthEvidenceBundleRepository.class,
                        AccountGameplayDelegationIssuanceRepository.class,
                        AccountGameplayDelegationResponseEnvelopeRepository.class,
                        AccountGameplayDelegationResponseEnvelopeService.class,
                        AccountJwtJwksConfigMapClient.class,
                        AccountJwtJwksTrustedSource.class,
                        AccountGameplayDelegationSigner.class,
                        AccountGameplayDelegationRedisClient.class,
                        AccountGameplayDelegationAuthorityProjection.class,
                        AccountGameplayDelegationTokenRegistry.class,
                        AccountGameplayDelegationIssuanceCommitService.class,
                        AccountGameplayDelegationCommittedIssuanceOwner.class,
                        AccountGameplayDelegationResponseRecoveryOwner.class,
                        AccountGameplayAuthenticateReceiver.class)) {
                  assertThat(context).hasSingleBean(owner);
                }

                var issuance = context.getBean(AccountGameplayDelegationIssuanceRepository.class);
                var signer = context.getBean(AccountGameplayDelegationSigner.class);
                var commits = context.getBean(AccountGameplayDelegationIssuanceCommitService.class);
                var recovery =
                    context.getBean(AccountGameplayDelegationResponseRecoveryOwner.class);
                var registry = context.getBean(AccountGameplayDelegationTokenRegistry.class);
                var envelopes =
                    context.getBean(AccountGameplayDelegationResponseEnvelopeRepository.class);
                assertThat(ReflectionTestUtils.getField(signer, "issuanceRepository"))
                    .isSameAs(issuance);
                assertThat(ReflectionTestUtils.getField(commits, "signer")).isSameAs(signer);
                assertThat(ReflectionTestUtils.getField(commits, "tokenRegistry"))
                    .isSameAs(registry);
                assertThat(ReflectionTestUtils.getField(recovery, "responseEnvelopes"))
                    .isSameAs(envelopes);
                assertThat(ReflectionTestUtils.getField(envelopes, "responseCryptography"))
                    .isSameAs(context.getBean("accountResponseEnvelopeCryptography"));
                var receiver = context.getBean(AccountGameplayAuthenticateReceiver.class);
                var login = ReflectionTestUtils.getField(receiver, "owner");
                assertThat(ReflectionTestUtils.getField(login, "commitOwner")).isSameAs(commits);
                assertThat(ReflectionTestUtils.getField(login, "responseOwner")).isSameAs(recovery);
                assertThat(ReflectionTestUtils.getField(receiver, "expectedWorkload"))
                    .isEqualTo("spiffe://firemud/ns/test/sa/game-session-service");
                assertThat(context.getBean(AcknowledgementRequirements.class))
                    .isEqualTo(new AcknowledgementRequirements(1, 2, 900));
                var catalog =
                    (RedisScriptCatalog)
                        ReflectionTestUtils.getField(
                            context.getBean(AccountGameplayDelegationRedisClient.class),
                            "scriptCatalog");
                assertThat(catalog).isNotNull();
                assertThat(catalog.require(AccountGenerationProjectionRedisContract.SCRIPT_ID))
                    .isEqualTo(AccountGenerationProjectionRedisContract.descriptor());
                assertThat(
                        catalog.require(AccountGameplayDelegationRedisClient.SCRIPT_ID).principal())
                    .isEqualTo("account_coord_app");
                assertThat(
                        catalog
                            .require(
                                AccountGameplayDelegationRedisClient
                                    .INITIAL_LOGIN_DISPOSAL_SCRIPT_ID)
                            .principal())
                    .isEqualTo("account_coord_app");
                assertThat(apiConstruction.constructed()).hasSize(1);
                verifyNoInteractions(context.getBean(DSLContext.class));
                verifyNoInteractions(context.getBean(AccountServiceImpl.class));
                verify(client, never()).connect(ByteArrayCodec.INSTANCE);
              });
      var options = ArgumentCaptor.forClass(ClientOptions.class);
      verify(client).setOptions(options.capture());
      assertThat(options.getValue().isAutoReconnect()).isFalse();
      verify(client).shutdown();
    }
  }

  @Test
  void absentMountSelectorsAndUnboundedRetentionFailWithoutOpeningConnections() {
    RedisClient client = mock(RedisClient.class);
    try (var redisFactory = mockStatic(RedisClient.class);
        var apiConstruction =
            mockConstruction(
                AccountJwtJwksApiBinding.class,
                (api, construction) ->
                    when(api.current())
                        .thenReturn(
                            AccountGameplayLoginProtectedSourcesConfigurationTest.apiBinding(
                                "test", "cluster-a")))) {
      redisFactory.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
      enabledContext()
          .withPropertyValues("firemud.account.gameplay-login.request-digest-keyring-path=")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Protected Account gameplay LOGIN configuration required");
                verify(client, never()).connect(ByteArrayCodec.INSTANCE);
              });
      enabledContext()
          .withPropertyValues("firemud.account.response-envelope.keyring-path=")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Protected Account gameplay LOGIN configuration required");
                verify(client, never()).connect(ByteArrayCodec.INSTANCE);
              });
      enabledContext()
          .withPropertyValues(
              "firemud.account.gameplay-login.registry-cleanup-margin-millis=300001")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Registry cleanup margin is outside its finite bound");
                verify(client, never()).connect(ByteArrayCodec.INSTANCE);
              });
      assertThat(apiConstruction.constructed()).isNotEmpty();
      verify(client, never()).connect(ByteArrayCodec.INSTANCE);
    }
  }

  private static ApplicationContextRunner enabledContext() {
    return new ApplicationContextRunner()
        .withUserConfiguration(CONFIGURATIONS)
        .withUserConfiguration(ExistingOwnerInfrastructure.class)
        .withPropertyValues(
            "firemud.account.gameplay-login.enabled=true",
            "firemud.grpc.workload-namespace=test",
            "firemud.account.public-jwks.api-binding-path="
                + AccountGameplayLoginProtectedSourcesConfigurationTest.API_PATH,
            "firemud.account.gameplay-login.coordination.uri="
                + "rediss://account_coord_app:test-only-password@coordination.example.invalid:6380/0",
            "firemud.account.gameplay-login.coordination.command-timeout-millis=800",
            "firemud.account.gameplay-login.coordination.required-local-aof-count=1",
            "firemud.account.gameplay-login.coordination.required-replica-aof-count=2",
            "firemud.account.gameplay-login.coordination.acknowledgement-timeout-millis=900",
            "firemud.account.gameplay-login.registry-cleanup-margin-millis=5000",
            "firemud.account.gameplay-login.request-digest-keyring-path=/not-read-by-configuration-test/digest",
            "firemud.account.response-envelope.keyring-path=/not-read-by-configuration-test/response");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ExistingOwnerInfrastructure {
    @Bean
    DSLContext dsl() {
      return mock(DSLContext.class);
    }

    @Bean
    AccountAuthorityGenerationRepository generations(DSLContext dsl) {
      return new AccountAuthorityGenerationRepository(dsl);
    }

    @Bean
    AccountAuthorityOutboxRepository outbox(DSLContext dsl) {
      return new AccountAuthorityOutboxRepository(dsl);
    }

    @Bean
    AccountAuthoritySourceEvidenceRepository sources(
        DSLContext dsl,
        AccountAuthorityGenerationRepository generations,
        AccountAuthorityOutboxRepository outbox) {
      return new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    }

    @Bean
    AccountRepository accounts(DSLContext dsl, AccountAuthoritySourceEvidenceRepository sources) {
      return new AccountRepository(dsl, sources);
    }

    @Bean
    AccountJwtSignerDesiredStateRepository signers(DSLContext dsl) {
      return new AccountJwtSignerDesiredStateRepository(dsl);
    }

    @Bean
    AccountJwtJwksPublicationRepository publications(
        DSLContext dsl, AccountJwtSignerDesiredStateRepository signers) {
      return new AccountJwtJwksPublicationRepository(dsl, signers);
    }

    @Bean
    AccountServiceImpl primaryCredentials() {
      return mock(AccountServiceImpl.class);
    }

    @Bean(name = "transactionManager")
    PlatformTransactionManager transactions() {
      return mock(PlatformTransactionManager.class);
    }

    @Bean
    AccountJwtSignerMaterializerTrustBinding materializerTrust() {
      var trust = mock(AccountJwtSignerMaterializerTrustBinding.class);
      when(trust.current())
          .thenReturn(
              Optional.of(
                  AccountGameplayLoginProtectedSourcesConfigurationTest.materializerBinding()));
      return trust;
    }

    @Bean
    AccountResponseEnvelopeCryptography accountResponseEnvelopeCryptography() {
      return new AccountResponseEnvelopeCryptography(
          new AccountResponseEnvelopeKeyring("/not-read-by-configuration-test/response"));
    }
  }
}
