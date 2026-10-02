package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only resolver for approved UUID associations to retained Game Session tenant keys. */
public final class RetainedRuntimeTenantUuidResolver {
  private static final Logger LOG =
      LoggerFactory.getLogger(RetainedRuntimeTenantUuidResolver.class);

  private final GameSessionRetainedTenantAssociationRepository associationRepository;
  private final String workloadNamespace;
  private final TransactionTemplate committedOutcomeRead;

  public RetainedRuntimeTenantUuidResolver(
      GameSessionRetainedTenantAssociationRepository associationRepository,
      String workloadNamespace,
      PlatformTransactionManager transactionManager) {
    this.workloadNamespace = Objects.requireNonNull(workloadNamespace, "workloadNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.committedOutcomeRead =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.committedOutcomeRead.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    this.committedOutcomeRead.setReadOnly(true);
  }

  private RetainedRuntimeTenantUuidResolver(String workloadNamespace) {
    this.associationRepository = null;
    this.workloadNamespace = workloadNamespace;
    this.committedOutcomeRead = null;
  }

  /** Creates an unavailable resolver when the configured namespace is absent or malformed. */
  public static RetainedRuntimeTenantUuidResolver denyOnly(String workloadNamespace) {
    if (workloadNamespace != null && GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("A valid namespace cannot use the deny-only resolver");
    }
    return new RetainedRuntimeTenantUuidResolver(workloadNamespace);
  }

  /** Resolves only the exact configured-namespace association; all uncertainty fails closed. */
  public Optional<UUID> resolveCanonicalTenantId(long legacyGameSessionTenantId) {
    if (legacyGameSessionTenantId <= 0L || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      return Optional.empty();
    }
    if (associationRepository == null || committedOutcomeRead == null) {
      return Optional.empty();
    }
    try {
      return Objects.requireNonNull(
          committedOutcomeRead.execute(
              status ->
                  associationRepository.readCanonicalTenantIdByRetainedTenantKey(
                      legacyGameSessionTenantId, workloadNamespace)));
    } catch (RuntimeException exception) {
      LOG.warn("Retained runtime tenant UUID association is unavailable", exception);
      return Optional.empty();
    }
  }
}
