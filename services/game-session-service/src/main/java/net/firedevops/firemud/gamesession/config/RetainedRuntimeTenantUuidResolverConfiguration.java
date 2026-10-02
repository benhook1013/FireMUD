package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshotRepository;
import net.firedevops.firemud.gamesession.service.RetainedRuntimeTenantUuidResolver;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires the immutable retained-association store only for the read-only runtime UUID resolver. */
@Configuration(proxyBeanMethods = false)
public class RetainedRuntimeTenantUuidResolverConfiguration {
  @Bean
  public RetainedRuntimeTenantUuidResolver retainedRuntimeTenantUuidResolver(
      DSLContext dsl,
      GameSessionRetainedTenantSnapshotRepository snapshotRepository,
      PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      return RetainedRuntimeTenantUuidResolver.denyOnly(workloadNamespace);
    }
    GameSessionRetainedTenantAssociationRepository associationRepository =
        new GameSessionRetainedTenantAssociationRepository(
            dsl, snapshotRepository, workloadNamespace);
    return new RetainedRuntimeTenantUuidResolver(
        associationRepository, workloadNamespace, transactionManager);
  }
}
