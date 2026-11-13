package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import java.util.List;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

/** Complete initial-LOGIN owner graph over the existing protected source implementations. */
@Import(AccountPublicJwksSourceConfiguration.class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.gameplay-login",
    name = "enabled",
    havingValue = "true")
public class AccountGameplayLoginSourceConfiguration {
  @Bean
  AccountAuthEvidenceBundleRepository accountGameplayLoginEvidenceBundles(
      DSLContext dsl,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox) {
    return new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);
  }

  @Bean
  AccountGameplayDelegationIssuanceRepository accountGameplayLoginIssuanceRepository(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountAuthEvidenceBundleRepository bundles) {
    return new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles);
  }

  @Bean
  AccountGameplayDelegationResponseEnvelopeRepository accountGameplayLoginResponseEnvelopes(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountAuthEvidenceBundleRepository bundles,
      AccountGameplayDelegationIssuanceRepository issuance,
      @Qualifier("accountResponseEnvelopeCryptography") AccountResponseEnvelopeCryptography crypto,
      @Value("${firemud.account.response-envelope.keyring-path:}") String responseKeyring) {
    AccountGameplayLoginProtectedSourcesConfiguration.requiredMount(responseKeyring);
    return new AccountGameplayDelegationResponseEnvelopeRepository(
        dsl, sources, bundles, issuance, crypto);
  }

  @Bean
  AccountGameplayDelegationResponseEnvelopeService accountGameplayLoginResponseEnvelopeService(
      AccountGameplayDelegationResponseEnvelopeRepository envelopes) {
    return new AccountGameplayDelegationResponseEnvelopeService(envelopes);
  }

  @Bean
  AccountGameplayDelegationSigner accountGameplayLoginSigner(
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountJwtSignerDesiredStateRepository signers,
      AccountJwtSignerMaterializerTrustBinding trust,
      AccountJwtJwksApiBinding api,
      AccountJwtJwksTrustedSource jwks,
      AccountGameplayDelegationResponseEnvelopeService envelopes,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountGameplayDelegationSigner(
        issuance, signers, trust, api, jwks, envelopes, transactions, Clock.systemUTC());
  }

  @Bean
  AccountGameplayDelegationRedisClient accountGameplayLoginRedis(
      @Qualifier(AccountGameplayLoginProtectedSourcesConfiguration.COORDINATION_CONNECTIONS)
          AccountCoordinationPinnedConnectionProvider connections,
      @Qualifier(AccountGameplayLoginProtectedSourcesConfiguration.ACKNOWLEDGEMENT_REQUIREMENTS)
          AcknowledgementRequirements acknowledgements) {
    return new AccountGameplayDelegationRedisClient(
        connections,
        RedisScriptCatalog.fromContributions(
            List.of(
                new AccountGameplayDelegationRedisScriptContribution(),
                new AccountGenerationProjectionRedisScriptContribution())),
        acknowledgements,
        Clock.systemUTC(),
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        AccountGameplayDelegationRedisClient.class.getClassLoader());
  }

  @Bean
  AccountGameplayDelegationAuthorityProjection accountGameplayLoginAuthorityProjection(
      AccountAuthoritySourceEvidenceRepository sources,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      AccountGameplayDelegationRedisClient redis) {
    return new AccountGameplayDelegationAuthorityProjection(sources, transactions, redis);
  }

  @Bean
  AccountGameplayDelegationTokenRegistry accountGameplayLoginTokenRegistry(
      AccountGameplayDelegationIssuanceRepository issuance,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      AccountGameplayDelegationRedisClient redis,
      @Value("${firemud.account.gameplay-login.registry-cleanup-margin-millis}")
          long cleanupMargin) {
    return new AccountGameplayDelegationTokenRegistry(
        issuance,
        transactions,
        redis,
        Clock.systemUTC(),
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        cleanupMargin);
  }

  @Bean
  AccountGameplayDelegationIssuanceCommitService accountGameplayLoginCommits(
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry registry,
      AccountGameplayDelegationAuthorityProjection projections,
      AccountGameplayDelegationIssuanceRepository issuance,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountGameplayDelegationIssuanceCommitService(
        signer, registry, projections, issuance, transactions, Clock.systemUTC());
  }

  @Bean
  AccountGameplayDelegationCommittedIssuanceOwner accountGameplayLoginCommittedIssuance(
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry registry,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountGameplayDelegationCommittedIssuanceOwner(
        issuance, signer, registry, transactions, Clock.systemUTC());
  }

  @Bean
  AccountGameplayDelegationResponseRecoveryOwner accountGameplayLoginResponseRecovery(
      AccountGameplayDelegationResponseEnvelopeRepository envelopes,
      AccountGameplayDelegationCommittedIssuanceOwner committed,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountGameplayDelegationResponseRecoveryOwner(envelopes, committed, transactions);
  }
}
