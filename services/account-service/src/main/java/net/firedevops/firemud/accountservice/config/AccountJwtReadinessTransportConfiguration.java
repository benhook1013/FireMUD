package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessGrpcReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessTransportOwner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessValidationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import org.springframework.beans.factory.ObjectProvider;
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

/** Explicit, default-inactive composition for the isolated JWT readiness RPCs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.validation",
    name = "enabled",
    havingValue = "true")
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public class AccountJwtReadinessTransportConfiguration {
  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtValidatorInventoryBinding.class)
  public AccountJwtValidatorInventoryBinding accountJwtValidatorInventoryBinding() {
    return new AccountJwtValidatorInventoryBinding(
        true, AccountJwtValidatorInventoryBinding.PROTECTED_BINDING_PATH.toString());
  }

  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtValidatorInventorySource.class)
  public AccountJwtValidatorInventorySource accountJwtValidatorInventorySource(
      @Qualifier("accountJwtReadinessJwksApiBinding") AccountJwtJwksApiBinding apiBinding,
      AccountJwtValidatorInventoryBinding inventoryBinding) {
    return new AccountJwtValidatorInventorySource(apiBinding, inventoryBinding, Clock.systemUTC());
  }

  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtReadinessReceiverInvocationPort.class)
  public AccountJwtReadinessReceiverInvocationPort accountJwtReadinessReceiverInvocationPort(
      ObjectProvider<SslBundles> sslBundles,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    SslBundles configuredBundles = sslBundles.getIfAvailable();
    if (configuredBundles != null
        && net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(
            workloadNamespace)) {
      return new AccountJwtReadinessGrpcReceiverInvocationPort(
          configuredBundles, workloadNamespace);
    }
    return AccountJwtReadinessReceiverInvocationPort.defaultDenied();
  }

  @Bean
  @Lazy
  @ConditionalOnMissingBean(AccountJwtReadinessProbeService.class)
  public AccountJwtReadinessProbeService accountJwtReadinessProbeService(
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
  public AccountJwtReadinessTransportOwner accountJwtReadinessTransportOwner(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessProbeService probeProducer,
      AccountJwtReadinessValidationService validationService,
      AccountJwtReadinessReceiverInvocationPort receiverInvocationPort,
      PlatformTransactionManager transactionManager) {
    return new AccountJwtReadinessTransportOwner(
        repository, probeProducer, validationService, transactionManager, receiverInvocationPort);
  }
}
