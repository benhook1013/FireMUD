package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Inactive orchestration seam for the durable-intent, World-acquire, exact-attach sequence.
 *
 * <p>This type is deliberately not registered as a Spring component. A retained attached or
 * terminal snapshot is historical intent evidence only; it does not establish a live World hold,
 * current lifecycle, or admission.
 */
public final class CanonicalInitialAdmissionHoldPreparation {

  public CanonicalInitialAdmissionHoldPreparation(
      CanonicalInitialAdmissionIntentRepository intentRepository,
      WorldCanonicalInitialAdmissionHoldClient worldClient,
      PlatformTransactionManager transactionManager) {
    Objects.requireNonNull(intentRepository, "intentRepository");
    Objects.requireNonNull(worldClient, "worldClient");
    Objects.requireNonNull(transactionManager, "transactionManager");
  }

  /**
   * Denies incomplete Human admission before intent reservation or remote World hold acquisition.
   */
  public CanonicalInitialAdmissionIntentSnapshot prepare(
      Request exactHoldRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request exactLifecycleRequest) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(exactHoldRequest, "exactHoldRequest");
    Objects.requireNonNull(exactLifecycleRequest, "exactLifecycleRequest");
    throw CanonicalInitialAdmissionRepository.unavailableHumanAdmissionProtection();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Initial-admission hold preparation cannot enter from an ambient transaction");
    }
  }
}
