package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired Game Session source intake. Only an authenticated exact Game Design read can reach local
 * persistence; this does not enroll runtime instances, admit gameplay, or authorize creator writes.
 */
public class GameSessionAuthoredWorldIntakeService {
  private final GameDesignRuntimeTenantIdentityClient sourceClient;
  private final GameSessionAuthoredWorldSourceRepository repository;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted owner configuration is validated before use; no resources or finalizer are acquired."
              + " The class remains non-final for transaction proxying.")
  public GameSessionAuthoredWorldIntakeService(
      GameDesignRuntimeTenantIdentityClient sourceClient,
      GameSessionAuthoredWorldSourceRepository repository,
      String workloadNamespace) {
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public IntakeReceipt intake(
      UUID intakeRequestId, UUID canonicalTenantId, UUID sourceOperationId, String worldSlug) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Authored-world intake requires its owner transaction");
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
    return repository.register(intakeRequestId, source);
  }

  private static void requireIdentity(UUID value, String name) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
