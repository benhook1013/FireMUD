package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.PreparedExpectedClosed;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable owner operation. Reservation and terminal mutation use short owner transactions; remote
 * World proof and owner readbacks run between them outside SQL.
 */
public final class DatabaseCanonicalInitialAdmissionService
    implements CanonicalInitialAdmissionService {
  private final CanonicalInitialAdmissionRepository repository;
  private final GameSessionCanonicalAdmissionPointerRepository pointerRepository;
  private final CanonicalInitialAdmissionWorldVerifier worldVerifier;
  private final TransactionTemplate ownerWriteTransaction;

  public DatabaseCanonicalInitialAdmissionService(
      CanonicalInitialAdmissionRepository repository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalInitialAdmissionWorldVerifier worldVerifier,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.pointerRepository = Objects.requireNonNull(pointerRepository, "pointerRepository");
    this.worldVerifier = Objects.requireNonNull(worldVerifier, "worldVerifier");
    this.ownerWriteTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.ownerWriteTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerWriteTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerWriteTransaction.setReadOnly(false);
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof bind(CanonicalInitialAdmissionRequest request) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    ownerWriteTransaction.executeWithoutResult(status -> repository.reserve(request));
    CanonicalInitialAdmissionOwnerProof beforeRemote =
        requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
    if (beforeRemote.outcome() != CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      return beforeRemote;
    }

    PreparedExpectedClosed preparedOrigin = prepareExpectedClosed(request);
    // This owner call is intentionally outside the repository transaction and can be retried by
    // the stable durable request identity after an unavailable or ambiguous response.
    CanonicalInitialAdmissionWorldProof worldProof = worldVerifier.verify(request);
    if (worldProof == null) {
      throw new IllegalStateException("World initial-admission verifier returned no owner proof");
    }
    worldProof.requireMatches(request);
    ownerWriteTransaction.executeWithoutResult(
        status -> repository.commit(request, worldProof, preparedOrigin));

    CanonicalInitialAdmissionOwnerProof result =
        requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
    if (result.outcome() == CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw new CanonicalInitialAdmissionRepository
          .CanonicalInitialAdmissionReconciliationRequiredException(
          "Initial-admission owner transaction did not produce an exact terminal result readback");
    }
    return result;
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof abort(
      CanonicalInitialAdmissionRequest request, String reason) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    ownerWriteTransaction.executeWithoutResult(status -> repository.reserve(request));
    CanonicalInitialAdmissionOwnerProof beforeAbort =
        requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
    if (beforeAbort.outcome() != CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      return beforeAbort;
    }
    PreparedExpectedClosed preparedOrigin = prepareExpectedClosed(request);
    ownerWriteTransaction.executeWithoutResult(
        status -> repository.abort(request, preparedOrigin, reason));
    return requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof read(String targetNamespace, String requestId) {
    return requireRead(targetNamespace, requestId);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical initial admission owner operation cannot enter from an ambient transaction");
    }
  }

  private PreparedExpectedClosed prepareExpectedClosed(CanonicalInitialAdmissionRequest request) {
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      return null;
    }
    return pointerRepository.prepareExpectedClosedForRealm(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.realmId(),
        request.expectedPriorPointerVersion(),
        request.expectedCatalogRevision());
  }

  private CanonicalInitialAdmissionOwnerProof requireRead(
      String targetNamespace, String requestId) {
    return repository
        .read(targetNamespace, requestId)
        .orElseThrow(
            () ->
                new CanonicalInitialAdmissionRepository
                    .CanonicalInitialAdmissionReconciliationRequiredException(
                    "Initial-admission attempt has no durable owner result"));
  }
}
