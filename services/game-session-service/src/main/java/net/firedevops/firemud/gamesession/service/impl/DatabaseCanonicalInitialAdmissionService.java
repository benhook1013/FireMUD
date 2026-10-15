package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Canonical Human mutation entry remains denied until Account terminal protection is available.
 * Explicit owner reads preserve their existing read-only boundary.
 */
public final class DatabaseCanonicalInitialAdmissionService
    implements CanonicalInitialAdmissionService {
  private final CanonicalInitialAdmissionRepository repository;

  public DatabaseCanonicalInitialAdmissionService(
      CanonicalInitialAdmissionRepository repository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalInitialAdmissionWorldVerifier worldVerifier,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(pointerRepository, "pointerRepository");
    Objects.requireNonNull(worldVerifier, "worldVerifier");
    Objects.requireNonNull(transactionManager, "transactionManager");
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof bind(CanonicalInitialAdmissionRequest request) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    throw CanonicalInitialAdmissionRepository.unavailableHumanAdmissionProtection();
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof abort(
      CanonicalInitialAdmissionRequest request, String reason) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    throw CanonicalInitialAdmissionRepository.unavailableHumanAdmissionProtection();
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
