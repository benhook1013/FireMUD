package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.client.GameSessionCanonicalInitialAdmissionOwnerClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered World owner for durable canonical first-admission exclusion and settlement. */
public final class WorldCanonicalInitialAdmissionHoldOwner {
  private final WorldCanonicalInitialAdmissionHoldRepository repository;
  private final GameSessionCanonicalInitialAdmissionOwnerClient gameSessionOwner;

  public WorldCanonicalInitialAdmissionHoldOwner(
      WorldCanonicalInitialAdmissionHoldRepository repository,
      GameSessionCanonicalInitialAdmissionOwnerClient gameSessionOwner) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.gameSessionOwner = Objects.requireNonNull(gameSessionOwner, "gameSessionOwner");
  }

  /** Acquires World-owned exclusion against the exact locked ACTIVE lifecycle epoch. */
  public HoldIdentity acquire(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    return repository.acquire(request, lifecycleRequest);
  }

  /** Reads the immutable retained identity without claiming mutable status or current admission. */
  public java.util.Optional<HoldIdentity> readIdentity(Request request) {
    return repository.readIdentity(request);
  }

  /** Reads hold state and current lifecycle from one read-only World snapshot. */
  public java.util.Optional<WorldCanonicalInitialAdmissionHoldState> readState(
      HoldIdentity identity, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    return repository.readState(identity, lifecycleRequest);
  }

  /**
   * Reads the exact Game Session owner outside SQL and settles only its exact terminal response.
   * Missing, pending, unavailable, or mismatched owner evidence leaves the durable hold pending.
   */
  public GameSessionCanonicalInitialAdmissionOwnerProof settleFromAuthenticatedOwnerRead(
      HoldIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Game Session owner proof must be read outside a World transaction");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        Objects.requireNonNull(
            gameSessionOwner.read(identity), "Game Session owner reader returned no proof");
    if (!identity.equals(proof.holdIdentity())) {
      throw new WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException(
          "Authenticated Game Session owner read changed the World hold identity");
    }
    repository.settle(identity, proof);
    return proof;
  }
}
