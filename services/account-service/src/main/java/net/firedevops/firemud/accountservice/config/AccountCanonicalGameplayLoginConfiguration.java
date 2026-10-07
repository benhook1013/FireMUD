package net.firedevops.firemud.accountservice.config;

import java.nio.file.Path;
import java.time.Clock;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCoordinationConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource;
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
import net.firedevops.firemud.accountservice.service.session.AccountMountedGameplayCredentialDigestKeySource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit, default-inactive composition of Account's credential-bound gameplay LOGIN owners.
 *
 * <p>This configuration contains only initial LOGIN issuance and recovery. It does not enroll or
 * promote a signer, activate a registry record, perform readiness work, or compose JOIN/admission
 * owners. Its lazy bean graph performs no network operation during application startup. Every
 * protected dependency is re-read by its owner when used; absent or withdrawn material therefore
 * leaves authentication unavailable rather than enabling a compatibility path.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AccountCanonicalGameplayLoginConfiguration.ProtectedConfigurationPresent.class)
public class AccountCanonicalGameplayLoginConfiguration {
  private static final String FEATURE_ENABLED = "firemud.account.gameplay-canonical-login.enabled";
  private static final String JWKS_API_ENABLED = "firemud.account.jwt-jwks-api.enabled";
  private static final String JWKS_API_BINDING =
      "firemud.account.jwt-jwks-api.protected-binding-path";
  private static final String MATERIALIZATION_ENABLED =
      "firemud.account.jwt-signer.materialization.enabled";
  private static final String MATERIALIZER_BINDING =
      "firemud.account.jwt-signer.materialization.protected-binding-path";
  private static final String RESPONSE_KEYRING = "firemud.account.response-envelope.keyring-path";
  private static final String DIGEST_KEYRING =
      "firemud.account.gameplay-canonical-login.digest-keyring-path";
  private static final String WORKLOAD_NAMESPACE = "firemud.grpc.workload-namespace";
  private static final String EXPECTED_JWKS_API_BINDING =
      "/etc/firemud/account-jwt-api/binding.json";
  private static final String EXPECTED_MATERIALIZER_BINDING =
      "/etc/firemud/account-jwt-materializer/binding.json";
  private static final int REQUIRED_LOCAL_AOF_COUNT = 1;
  private static final int REQUIRED_REPLICA_AOF_COUNT = 1;
  private static final int REDIS_ACKNOWLEDGEMENT_TIMEOUT_MILLIS = 1_000;

  @Bean(name = "accountCanonicalGameplayLoginJwksApiBinding")
  @Lazy
  public AccountJwtJwksApiBinding accountCanonicalGameplayLoginJwksApiBinding() {
    return new AccountJwtJwksApiBinding(true, EXPECTED_JWKS_API_BINDING);
  }

  @Bean
  @Lazy
  public AccountJwtJwksConfigMapClient accountCanonicalGameplayLoginJwksConfigMapClient(
      @Qualifier("accountCanonicalGameplayLoginJwksApiBinding")
          AccountJwtJwksApiBinding apiBinding) {
    return new AccountJwtJwksConfigMapClient(apiBinding);
  }

  @Bean
  @Lazy
  public AccountJwtJwksTrustedSource accountCanonicalGameplayLoginTrustedJwksSource(
      AccountJwtJwksConfigMapClient configMapClient,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      PlatformTransactionManager transactionManager) {
    return new AccountJwtJwksTrustedSource(
        configMapClient,
        materializerTrustBinding,
        desiredStateRepository,
        publicationRepository,
        transactionManager);
  }

  @Bean(destroyMethod = "close")
  @Lazy
  public AccountGameplayCoordinationConnectionProvider
      accountCanonicalGameplayLoginCoordinationConnectionProvider() {
    return new AccountGameplayCoordinationConnectionProvider(
        AccountGameplayCoordinationRedisBinding.loadProtected());
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationRedisClient accountCanonicalGameplayLoginRedisClient(
      AccountGameplayCoordinationConnectionProvider connectionProvider) {
    ClassLoader classLoader = AccountCanonicalGameplayLoginConfiguration.class.getClassLoader();
    return new AccountGameplayDelegationRedisClient(
        connectionProvider,
        RedisScriptCatalog.loadInstalled(classLoader),
        new AccountGameplayDelegationRedisClient.AcknowledgementRequirements(
            REQUIRED_LOCAL_AOF_COUNT,
            REQUIRED_REPLICA_AOF_COUNT,
            REDIS_ACKNOWLEDGEMENT_TIMEOUT_MILLIS),
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        classLoader);
  }

  @Bean
  @Lazy
  public AccountGameplayCredentialRequestDigestKeySource
      accountCanonicalGameplayLoginDigestKeySource(
          @Value("${" + DIGEST_KEYRING + "}") String mountPath) {
    return new AccountMountedGameplayCredentialDigestKeySource(
        Path.of(mountPath), Clock.systemUTC());
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationTokenRegistry accountCanonicalGameplayLoginTokenRegistry(
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountGameplayDelegationRedisClient coordinationRedis,
      @Value("${firemud.auth.session-safety-margin-ms:300000}") long cleanupMarginMillis) {
    return new AccountGameplayDelegationTokenRegistry(
        issuanceRepository,
        coordinationRedis,
        Clock.systemUTC(),
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        cleanupMarginMillis);
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationAuthorityProjection
      accountCanonicalGameplayLoginAuthorityProjection(
          AccountGameplayDelegationRedisClient coordinationRedis,
          AccountAuthoritySourceEvidenceRepository sourceEvidence,
          PlatformTransactionManager transactionManager,
          AccountAuthorityGenerationRepository generations,
          AccountTenantMembershipRepository memberships,
          AccountMembershipPairAuthorityRepository pairs,
          AccountAuthorityOutboxRepository outbox,
          AccountTenantAuthorityEventRepository tenantSources) {
    return new AccountGameplayDelegationAuthorityProjection(
        sourceEvidence,
        transactionManager,
        coordinationRedis,
        generations,
        memberships,
        pairs,
        outbox,
        tenantSources);
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationSigner accountCanonicalGameplayLoginSigner(
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      @Qualifier("accountCanonicalGameplayLoginJwksApiBinding") AccountJwtJwksApiBinding apiBinding,
      AccountJwtJwksTrustedSource trustedJwksSource,
      AccountGameplayDelegationResponseEnvelopeService responseEnvelopeService,
      PlatformTransactionManager transactionManager) {
    return new AccountGameplayDelegationSigner(
        issuanceRepository,
        desiredStateRepository,
        materializerTrustBinding,
        apiBinding,
        trustedJwksSource,
        responseEnvelopeService,
        transactionManager,
        Clock.systemUTC());
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationIssuanceCommitService accountCanonicalGameplayLoginCommitService(
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      AccountGameplayDelegationAuthorityProjection authorityProjection,
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      PlatformTransactionManager transactionManager) {
    return new AccountGameplayDelegationIssuanceCommitService(
        signer,
        tokenRegistry,
        authorityProjection,
        issuanceRepository,
        transactionManager,
        Clock.systemUTC());
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationCommittedIssuanceOwner
      accountCanonicalGameplayLoginCommittedIssuanceOwner(
          AccountGameplayDelegationIssuanceRepository issuanceRepository,
          AccountGameplayDelegationSigner signer,
          AccountGameplayDelegationTokenRegistry tokenRegistry,
          PlatformTransactionManager transactionManager) {
    return new AccountGameplayDelegationCommittedIssuanceOwner(
        issuanceRepository, signer, tokenRegistry, transactionManager, Clock.systemUTC());
  }

  @Bean
  @Lazy
  public AccountGameplayDelegationResponseRecoveryOwner
      accountCanonicalGameplayLoginResponseRecoveryOwner(
          AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes,
          AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner,
          PlatformTransactionManager transactionManager) {
    return new AccountGameplayDelegationResponseRecoveryOwner(
        responseEnvelopes, committedIssuanceOwner, transactionManager);
  }

  @Bean
  @Lazy
  public AccountGameplayCanonicalLoginOwner accountGameplayCanonicalLoginOwner(
      AccountRepository accounts,
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountGameplayCredentialRequestDigestKeySource digestKeys,
      AccountGameplayDelegationIssuanceCommitService commitOwner,
      AccountGameplayDelegationResponseRecoveryOwner responseOwner,
      PlatformTransactionManager transactionManager) {
    return new AccountGameplayCanonicalLoginOwner(
        accounts,
        issuanceRepository,
        digestKeys,
        commitOwner,
        responseOwner,
        transactionManager,
        Clock.systemUTC());
  }

  /** Checks only explicit configuration; protected material is validated by its real owner. */
  public static final class ProtectedConfigurationPresent implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      var environment = context.getEnvironment();
      if (!"true".equals(environment.getProperty(FEATURE_ENABLED))
          || !"true".equals(environment.getProperty(JWKS_API_ENABLED))
          || !EXPECTED_JWKS_API_BINDING.equals(environment.getProperty(JWKS_API_BINDING))
          || !"true".equals(environment.getProperty(MATERIALIZATION_ENABLED))
          || !EXPECTED_MATERIALIZER_BINDING.equals(environment.getProperty(MATERIALIZER_BINDING))) {
        return false;
      }

      if (!isAbsolutePath(environment.getProperty(RESPONSE_KEYRING))
          || !isAbsolutePath(environment.getProperty(DIGEST_KEYRING))) {
        return false;
      }
      String workloadNamespace = environment.getProperty(WORKLOAD_NAMESPACE);
      return workloadNamespace != null && GrpcPeerIdentity.isValidNamespace(workloadNamespace);
    }

    private static boolean isAbsolutePath(String value) {
      if (value == null || value.isBlank()) return false;
      try {
        return Path.of(value).isAbsolute();
      } catch (RuntimeException invalidPath) {
        return false;
      }
    }
  }
}
