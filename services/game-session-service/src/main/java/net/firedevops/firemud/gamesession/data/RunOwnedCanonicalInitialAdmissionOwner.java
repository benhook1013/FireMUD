package net.firedevops.firemud.gamesession.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof.Outcome;
import net.firedevops.firemud.gamesession.repository.CanonicalFixtureInitialAdmissionRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit fixed-capability canonical first-OPEN orchestration, without registration or scheduling.
 */
public final class RunOwnedCanonicalInitialAdmissionOwner {
  private final RunOwnedCanonicalLaunchPreparationOwner fixedSource;
  private final CanonicalFixtureInitialAdmissionRepository repository;
  private final WorldTransport world;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Required owner-scoped repository/DSL collaborators retain managed transaction identity; "
              + "they cannot be defensively copied and are not exposed by this owner.")
  public RunOwnedCanonicalInitialAdmissionOwner(
      RunOwnedCanonicalLaunchPreparationOwner fixedSource,
      CanonicalFixtureInitialAdmissionRepository repository,
      WorldTransport world) {
    this.fixedSource = Objects.requireNonNull(fixedSource);
    this.repository = Objects.requireNonNull(repository);
    this.world = Objects.requireNonNull(world);
  }

  public interface WorldTransport {
    HoldIdentity acquire(WorldFixtureInitialAdmissionHoldRequest request);

    void verifyActive(WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity hold);
  }

  public WorldFixtureInitialAdmissionTerminalProof open(
      WorldFixtureInitialAdmissionHoldRequest request) {
    requireOutsideTransaction();
    CanonicalFixtureInitialAdmissionRepository.requireFreshOrigin(request);
    fixedSource.loadFixedVerifiedEvidence();
    var retained = repository.readFixtureTerminal(request);
    if (retained.isPresent() && retained.get().outcome() != Outcome.PENDING) return retained.get();
    repository.reserveFixtureIntent(request);
    HoldIdentity hold = Objects.requireNonNull(world.acquire(request));
    repository.attachFixtureHold(request, hold);
    retained = repository.readFixtureTerminal(request);
    if (retained.isPresent() && retained.get().outcome() != Outcome.PENDING) return retained.get();
    repository.commitFixture(request, hold);
    return repository.readFixtureTerminal(request).orElseThrow();
  }

  /** Abort only an already retained World hold; absence never manufactures a terminal. */
  public WorldFixtureInitialAdmissionTerminalProof abort(
      WorldFixtureInitialAdmissionHoldRequest request, String reason) {
    requireOutsideTransaction();
    CanonicalFixtureInitialAdmissionRepository.requireFreshOrigin(request);
    var retained = repository.readFixtureTerminal(request).orElseThrow();
    if (retained.outcome() == Outcome.COMMITTED)
      throw new IllegalStateException("Committed fixture cannot abort");
    var hold = repository.readAttachedFixtureHold(request);
    repository.abortFixture(request, hold, reason);
    return repository.readFixtureTerminal(request).orElseThrow();
  }

  /** Exact same-namespace World peer and immutable actual leaf are checked before owner reads. */
  public Optional<WorldFixtureInitialAdmissionTerminalProof> readTerminalForWorldPeer(
      String namespace, String requestId) {
    requireOutsideTransaction();
    var source = fixedSource.loadFixedVerifiedEvidenceForWorldPeer();
    if (!source.sourceEvidence().targetNamespace().equals(namespace))
      throw new IllegalArgumentException("Fixture owner read namespace differs from fixed source");
    return repository.readFixtureTerminal(namespace, requestId);
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException(
          "Fixture owner transport cannot execute inside an ambient transaction");
  }
}
