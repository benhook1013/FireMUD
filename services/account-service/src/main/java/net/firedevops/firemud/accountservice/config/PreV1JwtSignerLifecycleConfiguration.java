package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksPrepublicationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.accountservice.service.session.PreV1SelfAttestedReadinessDriver;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pre-v1 composition (ADR 0185) of Account's interim JWT signer lifecycle for Compose smoke.
 *
 * <p>Nothing in production composes the bootstrap coordinator, validator inventory or readiness
 * probe service yet. Under the pre-v1 gate waiver this wires them against the Compose stand-ins and
 * runs the self-attested readiness driver so the materializer stand-in can promote a signer. The
 * real orchestration and per-Pod verification are owed before v1.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = PreV1GateWaivers.PROPERTY, havingValue = "true")
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-signer.materialization",
    name = "enabled",
    havingValue = "true")
@Import(AccountProtectedJwksSourceConfiguration.class)
public class PreV1JwtSignerLifecycleConfiguration {
  @Bean
  AccountJwtJwksPrepublicationService preV1JwtPrepublication(
      AccountJwtSignerDesiredStateRepository desiredState,
      AccountJwtJwksPublicationRepository publications,
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      AccountJwtJwksConfigMapClient configMaps,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountJwtJwksPrepublicationService(
        desiredState, publications, materializerTrust, configMaps, transactions);
  }

  @Bean
  AccountJwtValidatorInventorySource preV1JwtValidatorInventory(AccountJwtJwksApiBinding api) {
    return new AccountJwtValidatorInventorySource(
        api,
        new AccountJwtValidatorInventoryBinding(
            true, AccountJwtValidatorInventoryBinding.PROTECTED_BINDING_PATH.toString()),
        Clock.systemUTC());
  }

  @Bean
  AccountJwtReadinessProbeService preV1JwtReadinessProbes(
      AccountJwtReadinessProbeRepository readiness,
      AccountJwtValidatorInventorySource inventory,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountJwtReadinessProbeService(
        readiness, new TransactionTemplate(transactions), Clock.systemUTC(), inventory);
  }

  @Bean
  AccountJwtSignerBootstrapCoordinator preV1JwtSignerBootstrapCoordinator(
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      AccountJwtJwksConfigMapClient configMaps,
      AccountJwtSignerDesiredStateRepository desiredState,
      AccountJwtJwksPrepublicationService prepublication,
      AccountJwtReadinessProbeRepository readiness,
      ObjectProvider<AccountJwtValidatorInventorySource> inventory,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountJwtSignerBootstrapCoordinator(
        materializerTrust,
        configMaps,
        desiredState,
        prepublication,
        readiness,
        inventory,
        new TransactionTemplate(transactions));
  }

  @Bean
  PreV1SelfAttestedReadinessDriver preV1SelfAttestedReadinessDriver(
      PreV1GateWaivers waivers,
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      AccountJwtReadinessTrustBinding readinessTrust,
      AccountJwtSignerDesiredStateRepository desiredState,
      AccountJwtReadinessProbeRepository readiness,
      AccountJwtReadinessProbeService probes,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new PreV1SelfAttestedReadinessDriver(
        waivers,
        materializerTrust,
        readinessTrust,
        desiredState,
        readiness,
        probes,
        new TransactionTemplate(transactions),
        Clock.systemUTC());
  }

  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService preV1SelfAttestedReadinessSchedule(
      PreV1SelfAttestedReadinessDriver driver) {
    ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "pre-v1-self-attested-readiness");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(driver::driveOnce, 10, 5, TimeUnit.SECONDS);
    return executor;
  }
}
