package net.firedevops.firemud.accountservice.config;

import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import java.time.Clock;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionGrpcReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionLeaseOwner;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayCredentialOwner;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseManualFactory;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseWorkloadGuard;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the Account receivers Game Session's final PLAY admission calls: the player entitlement
 * read, the gameplay admission lease (with original-new bound credentials) and tenant capacity
 * (served through the shared AccountService registration). Each receiver authenticates the exact
 * Game Session workload. Paid hosting sources are not composed, so paid tenants fail closed; demo
 * tenants admit from their Account entitlement.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${firemud.account.canonical-realm-admission.enabled:false}"
        + " and ${firemud.account.gameplay-login.enabled:false}")
public class AccountGameplayAdmissionReceiversConfiguration {

  @Bean(initMethod = "init", destroyMethod = "close")
  AccountOriginalNewFinalAdmissionGrpcReadClient accountOriginalNewFinalAdmissionReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountOriginalNewFinalAdmissionGrpcReadClient(endpoints, tls, channels, namespace);
  }

  @Bean
  AccountGameplayAdmissionCallerVerifier accountGameplayAdmissionCallerVerifier(
      AccountJwtJwksTrustedSource jwks, AccountGameplayDelegationCommittedIssuanceOwner committed) {
    return new AccountGameplayAdmissionCallerVerifier(jwks, committed, Clock.systemUTC());
  }

  @Bean
  BindableService accountGameplayAdmissionLeaseReceiver(
      AccountGameplayAdmissionCallerVerifier verifier,
      CanonicalGameplayAdmissionDecisionReadClient decisions,
      DataSource dataSource,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry registry,
      @Qualifier("accountResponseEnvelopeCryptography") AccountResponseEnvelopeCryptography crypto,
      AccountOriginalNewFinalAdmissionGrpcReadClient finalReads,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    var leases = new AccountGameplayAdmissionLeaseOwner(verifier, decisions, namespace, dataSource);
    var credentials =
        new AccountOriginalNewBoundGameplayCredentialOwner(
            dataSource,
            namespace,
            verifier,
            signer,
            registry,
            crypto,
            Clock.systemUTC(),
            finalReads);
    var receiver =
        AccountGameplayAdmissionLeaseManualFactory.createUnregisteredReceiver(
            leases, credentials, namespace);
    var guard = new AccountGameplayAdmissionLeaseWorkloadGuard(namespace);
    return () ->
        ServerInterceptors.interceptForward(receiver, new GrpcPeerIdentityInterceptor(), guard);
  }

  @Bean
  AccountCanonicalRuntimeMembershipSnapshotRepository accountCanonicalRuntimeMembershipSnapshots(
      AccountRepository accounts,
      AccountAuthorityGenerationRepository generations,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountTenantAuthorityEventRepository tenantEvents,
      FreshTenantIdentityAssociationRepository freshTenants,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairs,
      AccountTenantMembershipRoleSnapshotRepository roles,
      AccountAuthorityOutboxRepository outbox) {
    return new AccountCanonicalRuntimeMembershipSnapshotRepository(
        accounts,
        generations,
        sources,
        tenantEvents,
        freshTenants,
        memberships,
        pairs,
        roles,
        outbox);
  }

  @Bean
  AccountPlayerAdmissionEntitlementGrpcService accountPlayerAdmissionEntitlementReceiver(
      DSLContext dsl,
      AccountRepository accounts,
      AccountGameplayAdmissionCallerVerifier verifier,
      AccountGameplayDelegationIssuanceRepository issuances,
      AccountCanonicalRuntimeMembershipSnapshotRepository memberships,
      AccountConnectScopeRepository scopes,
      AccountDemoTenantEntitlementRepository demos,
      AccountTenantAuthorityEventRepository tenantSources,
      CanonicalGameplayAdmissionDecisionReadClient decisions,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    var owner =
        new AccountPlayerAdmissionEntitlementOwner(
            dsl,
            accounts,
            verifier,
            issuances,
            memberships,
            scopes,
            null,
            null,
            demos,
            tenantSources,
            decisions,
            transactions,
            Clock.systemUTC(),
            namespace);
    return new AccountPlayerAdmissionEntitlementGrpcService(owner, namespace);
  }

  @Bean
  AccountTenantCapacityOwner accountTenantCapacityOwner(
      DSLContext dsl,
      AccountRepository accounts,
      AccountCanonicalRuntimeMembershipSnapshotRepository memberships,
      AccountGameplayDelegationIssuanceRepository issuances,
      AccountConnectScopeRepository scopes,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountTenantCapacityOwner(
        dsl, accounts, memberships, issuances, scopes, transactions, Clock.systemUTC(), namespace);
  }
}
