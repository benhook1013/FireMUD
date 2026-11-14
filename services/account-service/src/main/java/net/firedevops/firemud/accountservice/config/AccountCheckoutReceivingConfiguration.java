package net.firedevops.firemud.accountservice.config;

import io.lettuce.core.RedisURI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAccountAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceController;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceWebhookController;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanCatalog;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import net.firedevops.firemud.accountservice.security.AccountCheckoutHttpAuthInterceptor;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.impl.AccountControlUiAuthenticationGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiCoordination;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiRegistryContract;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiResponseCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiSignerOwner;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicyRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationService;
import net.firedevops.firemud.accountservice.service.session.LettuceAccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Real registered receiving under explicitly isolated inputs only. No provider, policy, key,
 * publication or credential is provisioned here; default production activation stays closed.
 */
@Configuration(proxyBeanMethods = false)
@Profile("isolated-checkout-receiving")
@ConditionalOnProperty(
    prefix = "firemud.account.checkout-receiving",
    name = "enabled",
    havingValue = "true")
@Import({
  AccountProtectedJwksSourceConfiguration.class,
  AccountHostingFirstInvoiceController.class,
  AccountHostingFirstInvoiceWebhookController.class
})
public class AccountCheckoutReceivingConfiguration {
  public static final String COORDINATION_URI = "accountCheckoutIsolatedCoordinationUri";
  public static final String ACKNOWLEDGEMENTS = "accountCheckoutIsolatedAcknowledgements";
  public static final String STRIPE = "accountCheckoutIsolatedStripeClient";

  @Bean
  AccountCheckoutReceivingProperties accountCheckoutReceivingProperties(Environment environment) {
    if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
      throw new IllegalArgumentException("Checkout receiving is restricted to isolated inputs");
    }
    return new AccountCheckoutReceivingProperties(
        environment.getRequiredProperty("firemud.grpc.workload-namespace"),
        Path.of(
            environment.getRequiredProperty(
                "firemud.account.checkout-receiving.private-signer-root")),
        Path.of(
            environment.getRequiredProperty(
                "firemud.account.checkout-receiving.public-signer-root")),
        Path.of(
            environment.getRequiredProperty(
                "firemud.account.checkout-receiving.control-ui-keyring-root")),
        environment.getProperty("firemud.auth.session-safety-margin-ms", Long.class, 300_000L));
  }

  @Bean
  AccountControlUiAccountAuthority accountCheckoutAuthority(
      AccountRepository accounts,
      AccountAuthoritySourceEvidenceRepository upstream,
      AccountPlatformRestrictionOperationRepository restrictions,
      DSLContext dsl) {
    return new AccountControlUiAccountAuthority(accounts, upstream, restrictions, dsl);
  }

  @Bean
  DraftAuthorizationFenceRepository accountCheckoutProducerFences(DSLContext dsl) {
    return new DraftAuthorizationFenceRepository(dsl);
  }

  @Bean
  AccountControlUiIssuanceRepository accountCheckoutIssuanceRepository(DSLContext dsl) {
    return new AccountControlUiIssuanceRepository(dsl);
  }

  @Bean
  AccountControlUiSignerOwner accountCheckoutSigner(
      AccountJwtSignerDesiredStateRepository signers,
      AccountJwtSignerMaterializerTrustBinding trust,
      AccountJwtJwksApiBinding api,
      AccountCheckoutReceivingProperties properties) {
    return new AccountControlUiSignerOwner(
        signers, trust, api, properties.privateSignerRoot(), properties.publicSignerRoot());
  }

  @Bean
  AccountControlUiResponseCryptography accountCheckoutCryptography(
      AccountCheckoutReceivingProperties properties) {
    return new AccountControlUiResponseCryptography(
        new AccountControlUiKeyring(properties.controlUiKeyringRoot()), Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  LettuceAccountCoordinationPinnedConnectionProvider accountCheckoutCoordinationConnections(
      @Qualifier(COORDINATION_URI) RedisURI deliveredIsolatedUri) {
    return new LettuceAccountCoordinationPinnedConnectionProvider(deliveredIsolatedUri);
  }

  @Bean
  AccountControlUiCoordination accountCheckoutCoordination(
      @Qualifier("accountCheckoutCoordinationConnections")
          AccountCoordinationPinnedConnectionProvider connections,
      @Qualifier(ACKNOWLEDGEMENTS)
          AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements
              acknowledgements) {
    RedisScriptContribution contribution =
        new RedisScriptContribution() {
          @Override
          public String ownerId() {
            return AccountControlUiRegistryContract.descriptor().owner();
          }

          @Override
          public java.util.Collection<
                  net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor>
              descriptors() {
            return List.of(AccountControlUiRegistryContract.descriptor());
          }
        };
    return new AccountControlUiCoordination(
        connections, acknowledgements, RedisScriptCatalog.fromContributions(List.of(contribution)));
  }

  @Bean
  AccountControlUiActorService accountCheckoutActors(
      AccountControlUiIssuanceRepository operations,
      AccountControlUiAccountAuthority authority,
      AccountControlUiSignerOwner signers,
      AccountControlUiCoordination coordination,
      AccountJwtJwksTrustedSource publicSource,
      DraftAuthorizationFenceRepository fences,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountControlUiActorService(
        operations,
        authority,
        signers,
        coordination,
        publicSource,
        fences,
        transactions,
        Clock.systemUTC());
  }

  @Bean
  AccountControlUiIssuanceService accountCheckoutIssuance(
      AccountServiceImpl primary,
      AccountControlUiIssuanceRepository operations,
      AccountControlUiAccountAuthority authority,
      DraftAuthorizationFenceRepository fences,
      AccountControlUiSignerOwner signers,
      AccountControlUiResponseCryptography cryptography,
      AccountControlUiCoordination coordination,
      AccountControlUiActorService actors,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      AccountCheckoutReceivingProperties properties) {
    return new AccountControlUiIssuanceService(
        primary,
        operations,
        authority,
        fences,
        signers,
        cryptography,
        coordination,
        actors,
        transactions,
        Clock.systemUTC(),
        properties.sessionSafetyMarginMillis(),
        properties.gatewayPeerUri());
  }

  @Bean
  AccountRecentReauthenticationService accountCheckoutReauthentication(
      AccountServiceImpl primary,
      AccountRepository accounts,
      AccountAuthorityGenerationRepository generations,
      DSLContext dsl,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      AccountControlUiResponseCryptography cryptography) {
    return new AccountRecentReauthenticationService(
        primary,
        accounts,
        generations,
        new AccountRecentReauthenticationRepository(dsl),
        new AccountRecentReauthenticationPolicyRepository(dsl),
        transactions,
        Clock.systemUTC(),
        cryptography);
  }

  @Bean
  AccountSubscriptionIntentRepository accountCheckoutIntents(
      DSLContext dsl, AccountHostingPlanCatalog plans) {
    return new AccountSubscriptionIntentRepository(dsl, plans);
  }

  @Bean
  AccountHostingFirstInvoicePaymentRepository accountCheckoutPayments(
      DSLContext dsl, AccountSubscriptionIntentRepository intents) {
    return new AccountHostingFirstInvoicePaymentRepository(dsl, intents, Clock.systemUTC());
  }

  @Bean
  AccountHostingSubscriptionLifecycleService accountCheckoutLifecycle(
      DSLContext dsl,
      AccountSubscriptionIntentRepository intents,
      AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits limits,
      AccountHostingSubscriptionLifecyclePolicy policy,
      AccountCheckoutReceivingProperties properties,
      @Qualifier(STRIPE) com.stripe.StripeClient stripe,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountHostingSubscriptionLifecycleService(
        new AccountHostingSubscriptionLifecycleRepository(
            dsl, intents, Clock.systemUTC(), limits, policy, properties.namespace(), false),
        new StripeHostingSubscriptionObserver(
            stripe, Clock.systemUTC(), Duration.ofSeconds(2), Duration.ofSeconds(5)),
        transactions);
  }

  @Bean
  AccountHostingFirstInvoicePaymentService accountCheckoutPaymentService(
      AccountControlUiActorService actors,
      AccountRecentReauthenticationService reauthentication,
      AccountHostingFirstInvoicePaymentRepository payments,
      DSLContext dsl,
      @Qualifier(STRIPE) com.stripe.StripeClient stripe,
      PaymentProperties properties,
      AccountHostingSubscriptionLifecycleService lifecycle,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountHostingFirstInvoicePaymentService(
        actors,
        reauthentication,
        payments,
        new StripeEventInboxRepository(dsl),
        new StripeHostingFirstInvoicePaymentClient(
            stripe, Clock.systemUTC(), Duration.ofSeconds(2), Duration.ofSeconds(5)),
        new StripeHostingWebhookVerifier(properties.getHostingWebhookSecret(), Clock.systemUTC()),
        lifecycle,
        transactions);
  }

  @Bean
  AccountControlUiAuthenticationGrpcService accountCheckoutAuthenticationGrpcService(
      AccountControlUiIssuanceService issuance,
      AccountControlUiActorService actors,
      AccountCheckoutReceivingProperties properties) {
    return new AccountControlUiAuthenticationGrpcService(issuance, actors, properties.namespace());
  }

  @Bean
  HttpJwtAuthInterceptor accountCheckoutHttpAuthentication(
      @Qualifier("jwtUtil") JwtUtil jwt,
      HttpAuthProperties properties,
      AccountControlUiActorService actors) {
    return new AccountCheckoutHttpAuthInterceptor(jwt, properties, actors);
  }

  @Bean
  @GlobalServerInterceptor
  AuthTokenInterceptor accountCheckoutGrpcAuthentication(
      @Qualifier("jwtUtil") JwtUtil jwt, GrpcAuthProperties properties) {
    var methods = new HashSet<>(properties.getPublicMethods());
    methods.add("account.v1.AccountControlUiAuthenticationService/AuthLogin");
    return new AuthTokenInterceptor(jwt, methods);
  }
}
