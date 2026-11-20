package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the canonical public REALMS issuer and JOIN scope consumers over the Game Session
 * admission decision read. Paid hosting sources are composed only by checkout receiving, so paid
 * tenants fail closed here; non-paid demo tenants are admitted from their Account entitlement.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.canonical-realm-admission",
    name = "enabled",
    havingValue = "true")
public class AccountCanonicalRealmAdmissionConfiguration {

  @Bean(initMethod = "init", destroyMethod = "close")
  CanonicalGameplayAdmissionDecisionReadClient canonicalGameplayAdmissionDecisionReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new CanonicalGameplayAdmissionDecisionReadClient(endpoints, tls, channels, namespace);
  }

  @Bean
  AccountPublicBootstrapEntitlementOwner accountPublicBootstrapEntitlementOwner(
      AccountTenantAuthorityEventRepository tenantSources,
      AccountDemoTenantEntitlementRepository demos,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountPublicBootstrapEntitlementOwner(
        tenantSources, null, demos, transactions, Clock.systemUTC());
  }

  @Bean
  AccountCanonicalPublicRealmScopeOwner accountCanonicalPublicRealmScopeOwner(
      @Qualifier("jwtUtil") JwtUtil jwt,
      AccountRepository accounts,
      SessionService sessions,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountPublicBootstrapEntitlementOwner entitlements,
      AccountTenantMembershipRepository memberships,
      FreshTenantIdentityAssociationRepository fresh,
      AccountTenantAuthorityEventRepository tenantSources,
      AccountConnectScopeRepository scopes,
      AccountTokenProperties tokens,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountCanonicalPublicRealmScopeOwner(
        new AccountPlayerBootstrapCallerVerifier(jwt, accounts, sessions),
        targets,
        entitlements,
        accounts,
        memberships,
        fresh,
        tenantSources,
        scopes,
        jwt,
        tokens,
        Clock.systemUTC(),
        transactions,
        namespace);
  }

  @Bean
  SmartInitializingSingleton accountCanonicalScopeConsumersComposition(
      AccountServiceImpl primary,
      @Qualifier("jwtUtil") JwtUtil jwt,
      AccountConnectScopeRepository scopes,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountTenantAuthorityEventRepository tenantSources,
      AccountDemoTenantEntitlementRepository demos,
      AccountCanonicalFirstJoinTerminalCoordinator firstJoin,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    Clock clock = Clock.systemUTC();
    var reader = new AccountCanonicalConnectScopeReader(jwt, scopes, transactions, clock);
    return () ->
        primary.setCanonicalScopeConsumers(
            reader, targets, tenantSources, demos, null, null, firstJoin, namespace, clock);
  }
}
