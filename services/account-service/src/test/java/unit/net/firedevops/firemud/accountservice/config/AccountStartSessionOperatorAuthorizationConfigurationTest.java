package unit.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.config.AccountStartSessionOperatorAuthorizationConfiguration;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.OwnerKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.Snapshot;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring-only test: the supplied mocks and fixed test key do not prove authority or runtime use.
 */
class AccountStartSessionOperatorAuthorizationConfigurationTest {
  private static final String OPT_IN =
      "firemud.account.start-session-operator-authorization.enabled";
  private static final String OPERATOR_KEYRING_PATH =
      "firemud.account.start-session-operator-authorization.response-envelope.keyring-path";
  private static final String GENERAL_KEYRING_PATH =
      "firemud.account.response-envelope.keyring-path";
  private static final String REFERENCE_LIFETIME =
      "firemud.account.start-session-operator-authorization.reference-lifetime";
  private static final String RESPONSE_RECOVERY_WINDOW =
      "firemud.account.start-session-operator-authorization.response-recovery-window";
  private static final String OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN =
      "startSessionOperatorResponseEnvelopeCryptography";

  @Test
  void ownerCompositionIsAbsentWhenTheOptInIsMissingOrFalse() {
    contextRunner()
        .withUserConfiguration(AccountStartSessionOperatorAuthorizationConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(AccountStartSessionOperatorAuthorizationService.class);
              assertThat(context)
                  .doesNotHaveBean(AccountOperatorAuthorizationFingerprintKeyring.class);
              assertThat(context).doesNotHaveBean(AccountResponseEnvelopeCryptography.class);
            });

    contextRunner()
        .withUserConfiguration(AccountStartSessionOperatorAuthorizationConfiguration.class)
        .withPropertyValues(OPT_IN + "=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(AccountStartSessionOperatorAuthorizationService.class);
            });
  }

  @Test
  void explicitOptInFailsClosedWhenOwnerAndKeySourcesAreUnavailable() {
    contextRunner()
        .withUserConfiguration(AccountStartSessionOperatorAuthorizationConfiguration.class)
        .withPropertyValues(
            OPT_IN + "=true",
            "firemud.grpc.workload-namespace=gameplay",
            "firemud.account.start-session-operator-authorization.reference-lifetime=PT2M",
            "firemud.account.start-session-operator-authorization.response-recovery-window=PT30S",
            "firemud.account.start-session-operator-authorization.fingerprint-post-expiry-retention=PT24H",
            "firemud.account.start-session-operator-authorization.response-envelope.keyring-path="
                + "/run/secrets/account-operator-response")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void explicitOptInUsesSuppliedCollaboratorsAndQualifiedOperatorCryptography() {
    enabledContext()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .hasSingleBean(AccountStartSessionOperatorAuthorizationService.class);
              assertThat(context)
                  .hasSingleBean(AccountOperatorAuthorizationFingerprintKeyring.class);
              assertThat(context.getBeansOfType(AccountResponseEnvelopeCryptography.class))
                  .hasSize(2)
                  .containsKeys(
                      "genericAccountResponseEnvelopeCryptography",
                      OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN);

              AccountResponseEnvelopeCryptography genericCryptography =
                  context.getBean(
                      "genericAccountResponseEnvelopeCryptography",
                      AccountResponseEnvelopeCryptography.class);
              AccountResponseEnvelopeCryptography operatorCryptography =
                  context.getBean(
                      OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN,
                      AccountResponseEnvelopeCryptography.class);
              assertThat(operatorCryptography).isNotSameAs(genericCryptography);
              assertThat(ReflectionTestUtils.getField(operatorCryptography, "keyring"))
                  .isNotSameAs(ReflectionTestUtils.getField(genericCryptography, "keyring"));
              assertThat(
                      ReflectionTestUtils.getField(
                          context.getBean(AccountStartSessionOperatorAuthorizationService.class),
                          "responseCryptography"))
                  .isSameAs(operatorCryptography);
            });
  }

  @Test
  void invalidNamespaceOrUnboundedTimingPreventsOwnerComposition() {
    enabledContext("", "PT2M", "PT30S", "/run/test/operator-response", "/run/test/general-response")
        .run(context -> assertThat(context).hasFailed());

    enabledContext(
            "gameplay",
            "PT6M",
            "PT30S",
            "/run/test/operator-response",
            "/run/test/general-response")
        .run(context -> assertThat(context).hasFailed());

    enabledContext(
            "gameplay", "PT2M", "PT2M", "/run/test/operator-response", "/run/test/general-response")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void missingOrReusedOperatorResponseMountPreventsOwnerComposition() {
    contextRunner()
        .withUserConfiguration(
            AccountStartSessionOperatorAuthorizationConfiguration.class,
            OwnerDependencies.class,
            GeneralResponseCryptography.class)
        .withPropertyValues(
            OPT_IN + "=true",
            "firemud.grpc.workload-namespace=gameplay",
            REFERENCE_LIFETIME + "=PT2M",
            RESPONSE_RECOVERY_WINDOW + "=PT30S",
            "firemud.account.start-session-operator-authorization.fingerprint-post-expiry-retention=PT24H")
        .run(context -> assertThat(context).hasFailed());

    enabledContext(
            "gameplay", "PT2M", "PT30S", "/run/test/shared-response", "/run/test/shared-response")
        .run(context -> assertThat(context).hasFailed());
  }

  private static ApplicationContextRunner enabledContext() {
    return enabledContext(
        "gameplay", "PT2M", "PT30S", "/run/test/operator-response", "/run/test/general-response");
  }

  private static ApplicationContextRunner enabledContext(
      String namespace,
      String referenceLifetime,
      String responseRecoveryWindow,
      String operatorKeyringPath,
      String generalKeyringPath) {
    return contextRunner()
        .withUserConfiguration(
            AccountStartSessionOperatorAuthorizationConfiguration.class,
            OwnerDependencies.class,
            GeneralResponseCryptography.class)
        .withPropertyValues(
            OPT_IN + "=true",
            "firemud.grpc.workload-namespace=" + namespace,
            REFERENCE_LIFETIME + "=" + referenceLifetime,
            RESPONSE_RECOVERY_WINDOW + "=" + responseRecoveryWindow,
            "firemud.account.start-session-operator-authorization.fingerprint-post-expiry-retention=PT24H",
            OPERATOR_KEYRING_PATH + "=" + operatorKeyringPath,
            GENERAL_KEYRING_PATH + "=" + generalKeyringPath);
  }

  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withInitializer(
            context ->
                context.getBeanFactory().setConversionService(new ApplicationConversionService()));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class OwnerDependencies {
    @Bean
    AccountControlUiActorService actors() {
      return mock(AccountControlUiActorService.class);
    }

    @Bean
    AccountControlUiIssuanceRepository issuanceOperations() {
      return mock(AccountControlUiIssuanceRepository.class);
    }

    @Bean
    StartSessionReservationEvidenceClient reservationEvidence() {
      return mock(StartSessionReservationEvidenceClient.class);
    }

    @Bean
    AccountStartSessionOperatorAuthorizationRepository repository() {
      return mock(AccountStartSessionOperatorAuthorizationRepository.class);
    }

    @Bean
    OwnerKeySource fingerprintOwnerKeySource() {
      // Fixed synthetic test bytes; no production or runtime key material is read or provisioned.
      return () ->
          new Snapshot(
              new KeyMaterial("test-active", new SecretKeySpec(new byte[32], "HmacSHA256")),
              List.of());
    }

    @Bean
    AccountHostedTermsService hostedTerms() {
      return mock(AccountHostedTermsService.class);
    }

    @Bean
    PlatformTransactionManager transactions() {
      return mock(PlatformTransactionManager.class);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class GeneralResponseCryptography {
    @Bean
    AccountResponseEnvelopeKeyring generalAccountResponseEnvelopeKeyring(
        @Value("${firemud.account.response-envelope.keyring-path:}") String keyringPath) {
      return new AccountResponseEnvelopeKeyring(keyringPath);
    }

    @Bean(name = "genericAccountResponseEnvelopeCryptography")
    AccountResponseEnvelopeCryptography genericAccountResponseEnvelopeCryptography(
        AccountResponseEnvelopeKeyring generalAccountResponseEnvelopeKeyring) {
      return new AccountResponseEnvelopeCryptography(generalAccountResponseEnvelopeKeyring);
    }
  }
}
