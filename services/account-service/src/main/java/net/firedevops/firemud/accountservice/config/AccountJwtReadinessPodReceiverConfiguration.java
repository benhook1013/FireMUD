package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodLocalIdentityProvider;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodReceiverEngine;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodReceiverProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeCrypto;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Default-false composition of Account's actual isolated per-Pod JWT readiness receiver. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.pod-receiver",
    name = "enabled",
    havingValue = "true")
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public class AccountJwtReadinessPodReceiverConfiguration {
  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtValidatorInventoryBinding.class)
  public AccountJwtValidatorInventoryBinding accountJwtPodReceiverInventoryBinding() {
    return new AccountJwtValidatorInventoryBinding(
        true, AccountJwtValidatorInventoryBinding.PROTECTED_BINDING_PATH.toString());
  }

  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtValidatorInventorySource.class)
  public AccountJwtValidatorInventorySource accountJwtPodReceiverInventorySource(
      @Qualifier("accountJwtReadinessJwksApiBinding") AccountJwtJwksApiBinding apiBinding,
      AccountJwtValidatorInventoryBinding inventoryBinding) {
    return new AccountJwtValidatorInventorySource(apiBinding, inventoryBinding, Clock.systemUTC());
  }

  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtReadinessProbeService.class)
  public AccountJwtReadinessProbeService accountJwtPodReceiverProbeService(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtValidatorInventorySource inventorySource,
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(false);
    return new AccountJwtReadinessProbeService(
        repository, transaction, Clock.systemUTC(), inventorySource);
  }

  @Bean
  @Lazy
  public AccountJwtReadinessPodReceiverWorkloadGuard accountJwtPodReceiverWorkloadGuard(
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      @Qualifier("accountJwtReadinessJwksApiBinding") AccountJwtJwksApiBinding apiBinding,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new AccountJwtReadinessPodReceiverWorkloadGuard(
        materializerTrustBinding, apiBinding, workloadNamespace);
  }

  @Bean
  @Lazy
  public AccountJwtReadinessPodLocalIdentityProvider accountJwtPodLocalIdentityProvider(
      AccountJwtValidatorInventorySource inventorySource,
      @Qualifier("accountJwtReadinessTrustedJwksSource")
          AccountJwtJwksTrustedSource trustedJwksSource,
      SslBundles sslBundles) {
    return new AccountJwtReadinessPodLocalIdentityProvider(
        inventorySource,
        trustedJwksSource,
        sslBundles,
        Clock.systemUTC(),
        System.getenv("HOSTNAME"));
  }

  @Bean
  @Lazy
  public AccountJwtReadinessPodReceiverProtoMapper accountJwtPodReceiverProtoMapper() {
    return new AccountJwtReadinessPodReceiverProtoMapper();
  }

  @Bean
  @Lazy
  public AccountAsymmetricJwtVerifier accountJwtPodReceiverProductionVerifier(
      @Qualifier("accountJwtReadinessTrustedJwksSource")
          AccountJwtJwksTrustedSource trustedJwksSource) {
    return AccountJwtReadinessProbeCrypto.createVerifier(trustedJwksSource, Clock.systemUTC());
  }

  @Bean
  @Lazy
  public AccountJwtReadinessPodReceiverEngine accountJwtPodReceiverEngine(
      AccountJwtReadinessProbeService probeService,
      AccountJwtReadinessPodReceiverWorkloadGuard workloadGuard,
      AccountJwtReadinessPodLocalIdentityProvider localIdentityProvider,
      AccountJwtReadinessPodReceiverProtoMapper protoMapper,
      @Qualifier("accountJwtPodReceiverProductionVerifier") AccountAsymmetricJwtVerifier verifier,
      @Value("${firemud.account.jwt-readiness.pod-receiver.max-control-ui-tenant-scopes}")
          int maxControlUiTenantScopes) {
    return new AccountJwtReadinessPodReceiverEngine(
        probeService,
        workloadGuard,
        localIdentityProvider,
        protoMapper,
        verifier,
        Clock.systemUTC(),
        maxControlUiTenantScopes);
  }
}
