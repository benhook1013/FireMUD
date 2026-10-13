package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksPrepublicationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Default-inactive composition for explicit Account JWT signer bootstrap reconciliation.
 *
 * <p>The enabled property only makes the callable coordinator available; it does not run it. No
 * startup runner, scheduler, or automatic signer activation is registered here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-signer.bootstrap",
    name = "enabled",
    havingValue = "true")
public class AccountJwtSignerBootstrapConfiguration {
  private static final String JWKS_API_BINDING_PATH = "/etc/firemud/account-jwt-api/binding.json";

  @Bean
  @Lazy
  public AccountJwtJwksApiBinding accountJwtSignerBootstrapJwksApiBinding(
      @Value("${firemud.account.jwt-jwks-api.enabled:false}") boolean enabled) {
    return new AccountJwtJwksApiBinding(enabled, JWKS_API_BINDING_PATH);
  }

  @Bean
  @Lazy
  public AccountJwtJwksConfigMapClient accountJwtSignerBootstrapJwksConfigMapClient(
      @Qualifier("accountJwtSignerBootstrapJwksApiBinding")
          AccountJwtJwksApiBinding accountJwtSignerBootstrapJwksApiBinding) {
    return new AccountJwtJwksConfigMapClient(accountJwtSignerBootstrapJwksApiBinding);
  }

  @Bean
  @Lazy
  public AccountJwtJwksPrepublicationService accountJwtSignerBootstrapPrepublicationService(
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      @Qualifier("accountJwtSignerBootstrapJwksConfigMapClient")
          AccountJwtJwksConfigMapClient accountJwtSignerBootstrapJwksConfigMapClient,
      PlatformTransactionManager transactionManager) {
    return new AccountJwtJwksPrepublicationService(
        desiredStateRepository,
        publicationRepository,
        materializerTrustBinding,
        accountJwtSignerBootstrapJwksConfigMapClient,
        transactionManager);
  }

  @Bean
  @Lazy
  public AccountJwtSignerBootstrapCoordinator accountJwtSignerBootstrapCoordinator(
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      @Qualifier("accountJwtSignerBootstrapJwksConfigMapClient")
          AccountJwtJwksConfigMapClient accountJwtSignerBootstrapJwksConfigMapClient,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtReadinessProbeRepository readinessProbeRepository,
      ObjectProvider<AccountJwtValidatorInventorySource> inventorySourceProvider,
      @Qualifier("accountJwtSignerBootstrapPrepublicationService")
          AccountJwtJwksPrepublicationService accountJwtSignerBootstrapPrepublicationService,
      PlatformTransactionManager transactionManager) {
    return new AccountJwtSignerBootstrapCoordinator(
        materializerTrustBinding,
        accountJwtSignerBootstrapJwksConfigMapClient,
        desiredStateRepository,
        accountJwtSignerBootstrapPrepublicationService,
        readinessProbeRepository,
        inventorySourceProvider,
        new TransactionTemplate(transactionManager));
  }
}
