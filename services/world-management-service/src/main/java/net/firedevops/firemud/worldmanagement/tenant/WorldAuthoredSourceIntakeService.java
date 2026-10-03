package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.ReadRequest;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired World owner intake. A fresh, authenticated Game Design source read precedes the local
 * transaction that retains its complete evidence and private tenant association.
 */
public class WorldAuthoredSourceIntakeService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AuthoredWorldSourceClient sourceClient;
  private final WorldAuthoredSourceIntakeRepository repository;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted owner configuration is validated before use; no resources or finalizer are "
              + "acquired.")
  public WorldAuthoredSourceIntakeService(
      AuthoredWorldSourceClient sourceClient,
      WorldAuthoredSourceIntakeRepository repository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    this.repository = Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Retains one fresh source binding. The intake request identity is stable across exact retries;
   * each first attempt gets a separate Game Design read identity.
   */
  public WorldAuthoredSourceIntakeReceipt intake(
      UUID intakeRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest) {
    requireAuthenticatedGameDesignCaller();
    requireNoAmbientTransaction();
    validateRequest(
        intakeRequestId,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        expectedSourceEvidenceDigest);

    Optional<WorldAuthoredSourceIntakeReceipt> prior =
        repository.read(workloadNamespace, intakeRequestId);
    if (prior.isPresent()) {
      return requireReceiptForRequest(
          prior.get(),
          intakeRequestId,
          canonicalTenantId,
          worldSlug,
          sourceOperationId,
          expectedSourceEvidenceDigest);
    }

    AuthoredWorldSourceEvidence source =
        sourceClient.read(
            new ReadRequest(
                workloadNamespace,
                newReadRequestId(intakeRequestId),
                sourceOperationId,
                canonicalTenantId,
                worldSlug));
    requireFreshSourceForRequest(
        source, canonicalTenantId, worldSlug, sourceOperationId, expectedSourceEvidenceDigest);

    WorldAuthoredSourceIntakeReceipt accepted =
        ownerTransaction.execute(
            status -> repository.acceptFresh(workloadNamespace, intakeRequestId, source));
    if (accepted == null) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "World owner transaction returned no authored-source receipt");
    }
    requireReceiptForRequest(
        accepted,
        intakeRequestId,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        expectedSourceEvidenceDigest,
        source);

    Optional<WorldAuthoredSourceIntakeReceipt> committed =
        repository.read(workloadNamespace, intakeRequestId);
    if (committed.isEmpty()) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "World authored-source intake is missing after commit readback");
    }
    WorldAuthoredSourceIntakeReceipt readback = committed.get();
    requireReceiptForRequest(
        readback,
        intakeRequestId,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        expectedSourceEvidenceDigest,
        source);
    if (!accepted.equals(readback)) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "World authored-source intake changed during commit readback");
    }
    return readback;
  }

  private void requireAuthenticatedGameDesignCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(workloadNamespace)) {
      throw new SecurityException(
          "World authored-source intake requires the authenticated Game Design workload");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World authored-source intake cannot enter from an ambient transaction");
    }
  }

  private void validateRequest(
      UUID intakeRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest) {
    requireNonNil(intakeRequestId, "intakeRequestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(sourceOperationId, "sourceOperationId");
    AuthoredWorldSourceDigest.validateReadSelector(workloadNamespace, canonicalTenantId, worldSlug);
    if (!GameTenantCreationDigest.isDigest(expectedSourceEvidenceDigest)) {
      throw new IllegalArgumentException(
          "expectedSourceEvidenceDigest must be a canonical SHA-256 digest");
    }
  }

  private void requireFreshSourceForRequest(
      AuthoredWorldSourceEvidence source,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest) {
    if (source == null
        || !workloadNamespace.equals(source.targetNamespace())
        || !canonicalTenantId.equals(source.canonicalTenantId())
        || !worldSlug.equals(source.worldSlug())
        || !sourceOperationId.equals(source.operationId())
        || !expectedSourceEvidenceDigest.equals(source.evidenceDigest())) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "Authenticated Game Design source does not match the requested World intake");
    }
    try {
      WorldAuthoredSourceIntakeDigest.requireFreshSource(workloadNamespace, source);
    } catch (IllegalArgumentException exception) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "Authenticated Game Design source is invalid or not fresh", exception);
    }
  }

  private WorldAuthoredSourceIntakeReceipt requireReceiptForRequest(
      WorldAuthoredSourceIntakeReceipt receipt,
      UUID intakeRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest) {
    return requireReceiptForRequest(
        receipt,
        intakeRequestId,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        expectedSourceEvidenceDigest,
        null);
  }

  private WorldAuthoredSourceIntakeReceipt requireReceiptForRequest(
      WorldAuthoredSourceIntakeReceipt receipt,
      UUID intakeRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest,
      AuthoredWorldSourceEvidence expectedSource) {
    if (receipt == null
        || !workloadNamespace.equals(receipt.targetNamespace())
        || !intakeRequestId.equals(receipt.intakeRequestId())
        || !canonicalTenantId.equals(receipt.canonicalTenantId())
        || !worldSlug.equals(receipt.worldSlug())
        || !sourceOperationId.equals(receipt.sourceOperationId())
        || !expectedSourceEvidenceDigest.equals(receipt.sourceEvidenceDigest())) {
      throw new WorldAuthoredSourceIntakeRepository.RegistrationConflictException(
          "World intake request identity was reused with changed scope or source evidence");
    }

    AuthoredWorldSourceEvidence source = receipt.source();
    if (expectedSource != null && !expectedSource.equals(source)) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "World authored-source receipt does not retain the complete source evidence");
    }
    requireFreshSourceForRequest(
        source, canonicalTenantId, worldSlug, sourceOperationId, expectedSourceEvidenceDigest);
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(workloadNamespace, intakeRequestId, source);
    String receiptDigest =
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            workloadNamespace,
            receipt.operationId(),
            requestDigest,
            source,
            receipt.localTenantKey());
    if (!requestDigest.equals(receipt.requestDigest())
        || !receiptDigest.equals(receipt.receiptDigest())) {
      throw new WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException(
          "World authored-source receipt digest readback is invalid");
    }
    return receipt;
  }

  private static UUID newReadRequestId(UUID intakeRequestId) {
    UUID readRequestId;
    do {
      readRequestId = UUID.randomUUID();
    } while (NIL_UUID.equals(readRequestId) || intakeRequestId.equals(readRequestId));
    return readRequestId;
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
