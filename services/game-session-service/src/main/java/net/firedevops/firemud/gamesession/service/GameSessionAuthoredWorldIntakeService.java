package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.InvalidIntakeEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.RegistrationConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired Game Session source intake. Fresh writes require an authenticated exact Game Design read;
 * exact retries use committed local evidence. This does not enroll runtime instances, admit
 * gameplay, or authorize creator writes.
 */
public class GameSessionAuthoredWorldIntakeService {
  private final GameDesignRuntimeTenantIdentityClient sourceClient;
  private final GameSessionAuthoredWorldSourceRepository repository;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted owner configuration is validated before use; no resources or finalizer are"
              + " acquired.")
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
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /** Reads immutable source evidence before opening the short, local owner write transaction. */
  public IntakeReceipt intake(
      UUID intakeRequestId, UUID canonicalTenantId, UUID sourceOperationId, String worldSlug) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Authored-world intake cannot enter from an ambient transaction");
    }
    requireIdentity(intakeRequestId, "intakeRequestId");
    requireIdentity(sourceOperationId, "sourceOperationId");
    AuthoredWorldSourceDigest.validateReadSelector(workloadNamespace, canonicalTenantId, worldSlug);

    var prior = repository.readByIntakeRequest(workloadNamespace, intakeRequestId);
    if (prior.isPresent()) {
      return requireRequestBinding(
          prior.orElseThrow(), intakeRequestId, canonicalTenantId, sourceOperationId, worldSlug);
    }

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
    IntakeReceipt written =
        ownerTransaction.execute(status -> repository.register(intakeRequestId, source));
    if (written == null) {
      throw new InvalidIntakeEvidenceException(
          "Game Session owner transaction returned no authored-world intake receipt");
    }

    var committed =
        repository.read(written.operationId(), canonicalTenantId, worldSlug, workloadNamespace);
    if (committed.isEmpty()) {
      throw new InvalidIntakeEvidenceException(
          "Game Session authored-world intake is missing after commit readback");
    }
    IntakeReceipt readback = committed.orElseThrow();
    requireRequestBinding(
        readback, intakeRequestId, canonicalTenantId, sourceOperationId, worldSlug);
    if (!written.equals(readback) || !source.equals(readback.source())) {
      throw new InvalidIntakeEvidenceException(
          "Game Session authored-world intake changed during commit readback");
    }
    return readback;
  }

  private IntakeReceipt requireRequestBinding(
      IntakeReceipt receipt,
      UUID intakeRequestId,
      UUID canonicalTenantId,
      UUID sourceOperationId,
      String worldSlug) {
    if (!receipt.intakeRequestId().equals(intakeRequestId)
        || !receipt.source().canonicalTenantId().equals(canonicalTenantId)
        || !receipt.source().operationId().equals(sourceOperationId)
        || !receipt.source().worldSlug().equals(worldSlug)
        || !receipt.source().targetNamespace().equals(workloadNamespace)) {
      throw new RegistrationConflictException(
          "Authored-world intake identity was reused with changed source scope");
    }
    return receipt;
  }

  private static void requireIdentity(UUID value, String name) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
