package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountPublicJwksReadGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountPublicJwksReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

class AccountPublicJwksSourceConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              AccountPublicJwksSourceConfiguration.class,
              AccountPublicJwksReadGrpcService.class,
              AdditionalSelectedConsumer.class);

  @Test
  void noSourceOrReceiverIsRegisteredByDefault() {
    context.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(AccountProtectedJwksSourceConfiguration.class);
          assertThat(ctx).doesNotHaveBean(AccountJwtJwksApiBinding.class);
          assertThat(ctx).doesNotHaveBean(AccountJwtJwksConfigMapClient.class);
          assertThat(ctx).doesNotHaveBean(AccountJwtJwksTrustedSource.class);
          assertThat(
                  AnnotationUtils.findAnnotation(
                      AccountProtectedJwksSourceConfiguration.class, Component.class))
              .isNull();
          assertThat(ctx).doesNotHaveBean(AccountPublicJwksReadGrpcService.class);
        });
  }

  @Test
  void explicitReadRequiresRealProtectedSources() {
    context
        .withPropertyValues("firemud.account.public-jwks-read.enabled=true")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"login", "read", "additional", "all"})
  void selectedConsumersImportExactlyOneProtectedGraphWithoutSelectingEachOther(String selection) {
    var trust = mock(AccountJwtSignerMaterializerTrustBinding.class);
    var signers = mock(AccountJwtSignerDesiredStateRepository.class);
    var publications = mock(AccountJwtJwksPublicationRepository.class);
    when(trust.current())
        .thenReturn(
            Optional.of(
                AccountGameplayLoginProtectedSourcesConfigurationTest.materializerBinding()));
    try (var construction =
        mockConstruction(
            AccountJwtJwksApiBinding.class,
            (api, ignored) ->
                when(api.current())
                    .thenReturn(
                        AccountGameplayLoginProtectedSourcesConfigurationTest.apiBinding(
                            "test", "cluster-a")))) {
      context
          .withPropertyValues(
              "firemud.account.gameplay-login.enabled="
                  + (selection.equals("login") || selection.equals("all")),
              "firemud.account.public-jwks-read.enabled="
                  + (selection.equals("read") || selection.equals("all")),
              "test.additional-protected-jwks-consumer.enabled="
                  + (selection.equals("additional") || selection.equals("all")),
              "firemud.grpc.workload-namespace=test",
              "firemud.account.public-jwks.api-binding-path="
                  + AccountGameplayLoginProtectedSourcesConfigurationTest.API_PATH)
          .withBean(AccountJwtSignerMaterializerTrustBinding.class, () -> trust)
          .withBean(AccountJwtSignerDesiredStateRepository.class, () -> signers)
          .withBean(AccountJwtJwksPublicationRepository.class, () -> publications)
          .withBean(
              "transactionManager",
              PlatformTransactionManager.class,
              () -> mock(PlatformTransactionManager.class))
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(AccountProtectedJwksSourceConfiguration.class);
                assertThat(ctx).hasSingleBean(AccountJwtJwksApiBinding.class);
                assertThat(ctx).hasSingleBean(AccountJwtJwksConfigMapClient.class);
                assertThat(ctx).hasSingleBean(AccountJwtJwksTrustedSource.class);
                if (selection.equals("read") || selection.equals("all")) {
                  assertThat(ctx).hasSingleBean(AccountPublicJwksReadService.class);
                  assertThat(ctx).hasSingleBean(AccountPublicJwksReadGrpcService.class);
                } else {
                  assertThat(ctx).doesNotHaveBean(AccountPublicJwksReadService.class);
                  assertThat(ctx).doesNotHaveBean(AccountPublicJwksReadGrpcService.class);
                }
                assertThat(ctx).doesNotHaveBean(AccountGameplayDelegationRedisClient.class);
                assertThat(construction.constructed()).hasSize(1);
                verifyNoInteractions(signers, publications);
              });
    }
  }

  /** Models another independently selected owner, without importing its business graph. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "test.additional-protected-jwks-consumer",
      name = "enabled",
      havingValue = "true")
  @Import(AccountProtectedJwksSourceConfiguration.class)
  static class AdditionalSelectedConsumer {}
}
