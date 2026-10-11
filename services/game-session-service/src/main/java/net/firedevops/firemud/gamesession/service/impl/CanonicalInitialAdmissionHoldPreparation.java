package net.firedevops.firemud.gamesession.service.impl;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.IntentState;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inactive orchestration seam for the durable-intent, World-acquire, exact-attach sequence.
 *
 * <p>This type is deliberately not registered as a Spring component. A retained attached or
 * terminal snapshot is historical intent evidence only; it does not establish a live World hold,
 * current lifecycle, or admission.
 */
public final class CanonicalInitialAdmissionHoldPreparation {
  private final CanonicalInitialAdmissionIntentRepository intentRepository;
  private final WorldCanonicalInitialAdmissionHoldClient worldClient;
  private final TransactionTemplate ownerTransaction;

  public CanonicalInitialAdmissionHoldPreparation(
      CanonicalInitialAdmissionIntentRepository intentRepository,
      WorldCanonicalInitialAdmissionHoldClient worldClient,
      PlatformTransactionManager transactionManager) {
    this.intentRepository = Objects.requireNonNull(intentRepository, "intentRepository");
    this.worldClient = Objects.requireNonNull(worldClient, "worldClient");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Persists the immutable request before acquiring the World hold, then attaches only the exact
   * World-issued identity in a second owner transaction.
   */
  public CanonicalInitialAdmissionIntentSnapshot prepare(
      Request exactHoldRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request exactLifecycleRequest) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(exactHoldRequest, "exactHoldRequest");
    Objects.requireNonNull(exactLifecycleRequest, "exactLifecycleRequest");

    CanonicalInitialAdmissionIntentSnapshot reserved =
        ownerTransaction.execute(
            status -> intentRepository.reserve(exactHoldRequest, exactLifecycleRequest));
    requireSnapshotMatches(reserved, exactHoldRequest, exactLifecycleRequest);

    // Replays retain the exact immutable historical snapshot. They do not check current World
    // state and therefore cannot authorize admission or alter a terminal outcome.
    if (reserved.state() != IntentState.PENDING_HOLD) {
      return reserved;
    }

    // TransactionTemplate returns only after commit; this network call is outside owner SQL.
    HoldIdentity acquired = worldClient.acquire(exactHoldRequest, exactLifecycleRequest);
    requireExactHoldIdentity(acquired, exactHoldRequest);

    CanonicalInitialAdmissionIntentSnapshot attached =
        ownerTransaction.execute(status -> intentRepository.attach(exactHoldRequest, acquired));
    requireSnapshotMatches(attached, exactHoldRequest, exactLifecycleRequest);
    if (attached.state() != IntentState.HOLD_ATTACHED
        || attached.holdIdentity() == null
        || !Arrays.equals(attached.holdIdentity().canonicalBytes(), acquired.canonicalBytes())) {
      throw new IllegalStateException(
          "Attached initial-admission intent did not retain the exact World hold identity");
    }
    return attached;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Initial-admission hold preparation cannot enter from an ambient transaction");
    }
  }

  private static void requireSnapshotMatches(
      CanonicalInitialAdmissionIntentSnapshot snapshot,
      Request holdRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    if (snapshot == null
        || !Arrays.equals(
            snapshot.holdRequest().canonicalRequestBytes(), holdRequest.canonicalRequestBytes())
        || !Arrays.equals(
            snapshot.lifecycleRequest().canonicalBytes(), lifecycleRequest.canonicalBytes())) {
      throw new IllegalStateException(
          "Durable initial-admission intent readback changed the exact request tuple");
    }
  }

  private static void requireExactHoldIdentity(HoldIdentity identity, Request request) {
    if (identity == null
        || !Arrays.equals(identity.canonicalRequestBytes(), request.canonicalRequestBytes())
        || !Arrays.equals(
            identity.request().canonicalRequestBytes(), request.canonicalRequestBytes())
        || !identity.holdBindingDigest().equals(request.holdBindingDigest())) {
      throw new IllegalStateException(
          "World initial-admission hold identity did not echo the exact retained request");
    }
  }
}
