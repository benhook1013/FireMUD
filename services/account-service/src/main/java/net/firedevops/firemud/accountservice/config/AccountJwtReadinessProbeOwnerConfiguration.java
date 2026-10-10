package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverMetadataService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Default-false composition for isolated protected readiness owner and metadata RPCs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.probe-owner",
    name = "enabled",
    havingValue = "true")
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public class AccountJwtReadinessProbeOwnerConfiguration {
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
  public AccountJwtReadinessProbeOwnerProtoMapper accountJwtReadinessProbeOwnerProtoMapper() {
    return new AccountJwtReadinessProbeOwnerProtoMapper();
  }

  @Bean
  @Lazy
  public AccountJwtReadinessProbeOwnerWorkloadGuard accountJwtReadinessProbeOwnerWorkloadGuard(
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      @Qualifier("accountJwtReadinessJwksApiBinding") AccountJwtJwksApiBinding apiBinding,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new AccountJwtReadinessProbeOwnerWorkloadGuard(
        materializerTrustBinding, apiBinding, workloadNamespace);
  }

  @Bean
  @Lazy
  public AccountJwtReadinessProbeOwnerService accountJwtReadinessProbeOwnerService(
      AccountJwtReadinessProbeService probeService,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard,
      AccountJwtReadinessProbeOwnerProtoMapper protoMapper) {
    return new AccountJwtReadinessProbeOwnerService(
        probeService, materializerTrustBinding, workloadGuard, protoMapper);
  }

  @Bean
  @Lazy
  public AccountJwtReadinessReceiverMetadataService accountJwtReadinessReceiverMetadataService(
      AccountJwtReadinessProbeService probeService,
      AccountJwtValidatorInventorySource inventorySource,
      @Qualifier("accountJwtReadinessTrustedJwksSource")
          AccountJwtJwksTrustedSource trustedJwksSource,
      AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtReadinessProbeOwnerProtoMapper protoMapper) {
    return new AccountJwtReadinessReceiverMetadataService(
        probeService,
        inventorySource,
        trustedJwksSource,
        workloadGuard,
        materializerTrustBinding,
        protoMapper,
        Clock.systemUTC());
  }
}
