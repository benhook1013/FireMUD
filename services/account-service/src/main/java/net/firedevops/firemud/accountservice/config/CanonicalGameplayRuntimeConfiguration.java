package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountInitialGameplayLoginDisposalRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAuthenticateReceiver;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayPrimaryCredentialVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountInitialGameplayLoginDisposalOwner;
import net.firedevops.firemud.accountservice.service.session.AccountMountedGameplayCredentialDigestKeySource;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Receiving composition over the actual protected issuance, recovery and registry owners. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.gameplay-login",
    name = "enabled",
    havingValue = "true")
public class CanonicalGameplayRuntimeConfiguration {
  @Bean
  AccountGameplayAuthenticateReceiver canonicalGameplayAuthenticateReceiver(
      AccountRepository accounts,
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayDelegationIssuanceCommitService commits,
      AccountGameplayDelegationResponseRecoveryOwner recovery,
      AccountGameplayDelegationTokenRegistry registry,
      AccountServiceImpl primary,
      DSLContext dsl,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      @Value("${firemud.account.gameplay-login.request-digest-keyring-path:}") String digestKeyring,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    var clock = Clock.systemUTC();
    var digestKeys =
        new AccountMountedGameplayCredentialDigestKeySource(
            AccountGameplayLoginProtectedSourcesConfiguration.requiredMount(digestKeyring), clock);
    var owner =
        new AccountGameplayCanonicalLoginOwner(
            accounts, issuance, digestKeys, commits, recovery, transactions, clock);
    var disposal =
        new AccountInitialGameplayLoginDisposalOwner(
            new AccountInitialGameplayLoginDisposalRepository(dsl), registry, transactions);
    return new AccountGameplayAuthenticateReceiver(
        owner, new AccountGameplayPrimaryCredentialVerifier(primary), namespace, disposal);
  }
}
