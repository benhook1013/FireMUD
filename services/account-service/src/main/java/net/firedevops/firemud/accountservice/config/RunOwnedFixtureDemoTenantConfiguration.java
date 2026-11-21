package net.firedevops.firemud.accountservice.config;

import java.io.IOException;
import java.nio.file.Path;
import net.firedevops.firemud.accountservice.client.GameDesignFreshTenantIdentityClient;
import net.firedevops.firemud.accountservice.data.RunOwnedFixtureDemoTenantEnrollmentDriver;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.FreshTenantIdentityEnrollmentService;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Enrolls the run-owned Compose fixture tenant and its demo entitlement into Account. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
public class RunOwnedFixtureDemoTenantConfiguration {

  @Bean(destroyMethod = "close")
  GameDesignFreshTenantIdentityClient runOwnedFixtureGameDesignTenantIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      @Value("${firemud.grpc.workload-namespace:}") String namespace)
      throws IOException {
    var client = new GameDesignFreshTenantIdentityClient(endpoints, tls, channels, namespace);
    client.init();
    return client;
  }

  @Bean
  RunOwnedFixtureDemoTenantEnrollmentDriver runOwnedFixtureDemoTenantEnrollmentDriver(
      GameDesignFreshTenantIdentityClient gameDesign,
      FreshTenantIdentityAssociationRepository fresh,
      AccountAuthorityGenerationRepository generations,
      AccountDemoTenantEntitlementRepository demos,
      DSLContext dsl,
      Environment environment,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:}") String capabilityPath,
      @Value("${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:}") String receiptPath,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String composeProjectName) {
    var importTransaction = new TransactionTemplate(transactions);
    importTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    importTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    return new RunOwnedFixtureDemoTenantEnrollmentDriver(
        new FreshTenantIdentityEnrollmentService(gameDesign, fresh, generations, importTransaction),
        demos,
        dsl,
        PreV1GateWaivers.from(environment).enabled(),
        transactions,
        Path.of(capabilityPath),
        Path.of(receiptPath),
        runId,
        composeProjectName);
  }
}
