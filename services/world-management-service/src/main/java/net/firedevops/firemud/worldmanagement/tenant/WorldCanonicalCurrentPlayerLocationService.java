package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered, default-denied reader for exact World-owned initial actor placement. */
public final class WorldCanonicalCurrentPlayerLocationService {
  private final WorldCanonicalCurrentPlayerLocationRepository repository;
  private final WorldCanonicalInitialPlayerLocationService.PlacementAuthorityVerifier verifier;

  /** Production-safe default while authenticated actor/admission owner proofs are unavailable. */
  public WorldCanonicalCurrentPlayerLocationService(
      WorldCanonicalCurrentPlayerLocationRepository repository) {
    this(repository, WorldCanonicalCurrentPlayerLocationService::denyByDefault);
  }

  /** Explicit verifier injection is intended for a real owner producer or isolated proof only. */
  public WorldCanonicalCurrentPlayerLocationService(
      WorldCanonicalCurrentPlayerLocationRepository repository,
      WorldCanonicalInitialPlayerLocationService.PlacementAuthorityVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Requires current actor, Account and Game Session authority for every read. A retained placement
   * result alone is never current caller authority.
   */
  public Optional<WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation> read(
      WorldCanonicalInitialPlayerLocation.Request request) {
    Objects.requireNonNull(request, "request");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World current-location read must verify authority outside an ambient transaction");
    }
    var held =
        Objects.requireNonNull(
            verifier.verifyAndHold(request),
            "Current-location verifier returned no held authority");
    try (held) {
      held.requireHeld();
      var current = repository.read(request, held);
      held.requireHeld();
      return current;
    }
  }

  private static WorldCanonicalInitialPlayerLocationService.HeldPlacementAuthority denyByDefault(
      WorldCanonicalInitialPlayerLocation.Request request) {
    throw new WorldCanonicalInitialPlayerLocationService.PlacementDeniedException(
        "World current-location read has no authenticated current Entity assignment, Account, and Game Session authority producer");
  }
}
