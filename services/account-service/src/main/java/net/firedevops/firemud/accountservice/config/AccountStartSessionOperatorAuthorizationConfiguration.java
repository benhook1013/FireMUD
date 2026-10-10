package net.firedevops.firemud.accountservice.config;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.OwnerKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit, disabled-by-default composition for Account's human StartSession authorization owner.
 *
 * <p>Enabling this composition requires real Account owner sources, trusted key custody, and
 * configured bounded timing. This configuration supplies no actor, hosted-terms, fingerprint-key,
 * or response-encryption defaults.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = AccountStartSessionOperatorAuthorizationConfiguration.PROPERTY_PREFIX,
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class AccountStartSessionOperatorAuthorizationConfiguration {
  static final String PROPERTY_PREFIX = "firemud.account.start-session-operator-authorization";
  static final String OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN =
      "startSessionOperatorResponseEnvelopeCryptography";

  /** Creates Account's explicit active/retained HMAC calculator from an owner-supplied source. */
  @Bean
  public AccountOperatorAuthorizationFingerprintKeyring
      accountStartSessionOperatorFingerprintKeyring(
          OwnerKeySource ownerKeySource,
          @Value(
                  "${firemud.account.start-session-operator-authorization.fingerprint-post-expiry-retention}")
              Duration requiredPostExpiryRetention) {
    return new AccountOperatorAuthorizationFingerprintKeyring(
        ownerKeySource, Clock.systemUTC(), requiredPostExpiryRetention);
  }

  /**
   * Creates a separate response-envelope cryptography instance from the operator-only mount.
   *
   * <p>The generic Account envelope bean and control-ui cryptography are deliberately not injected
   * or reused. Deployment remains responsible for supplying a distinct protected key mount.
   */
  @Bean(name = OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN)
  @Qualifier(OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN)
  public AccountResponseEnvelopeCryptography
      accountStartSessionOperatorResponseEnvelopeCryptography(
          @Value(
                  "${firemud.account.start-session-operator-authorization.response-envelope.keyring-path}")
              String operatorKeyringPath,
          @Value("${firemud.account.response-envelope.keyring-path:}")
              String generalAccountKeyringPath) {
    Path operatorMount = requiredAbsoluteMount(operatorKeyringPath, "operator response keyring");
    if (generalAccountKeyringPath != null && !generalAccountKeyringPath.isBlank()) {
      Path generalMount = Path.of(generalAccountKeyringPath).toAbsolutePath().normalize();
      if (operatorMount.equals(generalMount)) {
        throw new IllegalArgumentException(
            "Operator response keyring must use its separately supplied mount");
      }
    }
    return new AccountResponseEnvelopeCryptography(
        new AccountResponseEnvelopeKeyring(operatorMount.toString()));
  }

  /** Wires only genuine owner collaborators and a same-namespace workload policy. */
  @Bean
  public AccountStartSessionOperatorAuthorizationService
      accountStartSessionOperatorAuthorizationService(
          AccountControlUiActorService actors,
          AccountControlUiIssuanceRepository issuanceOperations,
          StartSessionReservationEvidenceClient reservationEvidence,
          AccountStartSessionOperatorAuthorizationRepository repository,
          AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys,
          @Qualifier(OPERATOR_RESPONSE_CRYPTOGRAPHY_BEAN)
              AccountResponseEnvelopeCryptography operatorResponseCryptography,
          AccountHostedTermsService hostedTerms,
          PlatformTransactionManager transactions,
          @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace,
          @Value("${firemud.account.start-session-operator-authorization.reference-lifetime}")
              Duration referenceLifetime,
          @Value("${firemud.account.start-session-operator-authorization.response-recovery-window}")
              Duration responseRecoveryWindow) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "A valid configured workload namespace is required for operator authorization");
    }
    String peerPrefix = "spiffe://firemud/ns/" + workloadNamespace + "/sa/";
    return new AccountStartSessionOperatorAuthorizationService(
        actors,
        issuanceOperations,
        reservationEvidence,
        repository,
        fingerprintKeys,
        operatorResponseCryptography,
        hostedTerms,
        transactions,
        Clock.systemUTC(),
        new SecureRandom(),
        peerPrefix + "logging-admin-service",
        peerPrefix + "game-session-service",
        referenceLifetime,
        responseRecoveryWindow);
  }

  private static Path requiredAbsoluteMount(String configuredPath, String field) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException("Explicit " + field + " mount is required");
    }
    Path path = Path.of(configuredPath);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Absolute " + field + " mount is required");
    }
    return path.normalize();
  }
}
