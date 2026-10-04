package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired Game Session source intake. Only an authenticated exact Game Design read can reach local
 * persistence; this does not enroll runtime instances, admit gameplay, or authorize creator writes.
 */
public class GameSessionAuthoredWorldIntakeService {
  private final GameDesignRuntimeTenantIdentityClient sourceClient;
  private final GameSessionAuthoredWorldSourceRepository repository;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted owner configuration is validated before use; no resources or finalizer are acquired.")
  public GameSessionAuthoredWorldIntakeService(
      GameDesignRuntimeTenantIdentityClient sourceClient,
      GameSessionAuthoredWorldSourceRepository repository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    this.repository = Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** Resolves the authenticated source before opening a short local owner transaction. */
  public IntakeReceipt intake(
      UUID intakeRequestId, UUID canonicalTenantId, UUID sourceOperationId, String worldSlug) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source read must begin outside an owner transaction");
    }
    requireIdentity(intakeRequestId, "intakeRequestId");
    requireIdentity(sourceOperationId, "sourceOperationId");
    AuthoredWorldSourceDigest.validateReadSelector(workloadNamespace, canonicalTenantId, worldSlug);
    // A response echo is scoped to this read attempt, not the durable local intake request.
    var source =
        sourceClient.resolveAuthoredWorldSource(
            canonicalTenantId.toString(),
            sourceOperationId.toString(),
            worldSlug,
            UUID.randomUUID().toString());
    if (source == null
        || !workloadNamespace.equals(source.targetNamespace())
        || !canonicalTenantId.equals(source.canonicalTenantId())
        || !sourceOperationId.equals(source.operationId())
        || !worldSlug.equals(source.worldSlug())) {
      throw new IllegalStateException("Authenticated authored-world source does not match intake");
    }
    return ownerTransaction.execute(status -> repository.register(intakeRequestId, source));
  }

  private static void requireIdentity(UUID value, String name) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
