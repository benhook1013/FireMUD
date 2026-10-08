package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, default-denied World initial-placement producer. A real Entity/Account/Game Session
 * authenticated producer must supply the held verifier before this local slice can write.
 */
public final class WorldCanonicalInitialPlayerLocationService {
  private final WorldCanonicalInitialPlayerLocationRepository repository;
  private final PlacementAuthorityVerifier verifier;

  /** Production-safe default while authenticated actor-assignment delivery is unavailable. */
  public WorldCanonicalInitialPlayerLocationService(
      WorldCanonicalInitialPlayerLocationRepository repository) {
    this(repository, WorldCanonicalInitialPlayerLocationService::denyByDefault);
  }

  /** Explicit authority injection is intended for a genuine owner producer or isolated proof. */
  public WorldCanonicalInitialPlayerLocationService(
      WorldCanonicalInitialPlayerLocationRepository repository,
      PlacementAuthorityVerifier verifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Requires current actor/Account/admission authority on every attempt, including exact retries;
   * an immutable historical placement receipt alone never authorizes the caller.
   */
  public WorldCanonicalInitialPlayerLocation.Result place(
      WorldCanonicalInitialPlayerLocation.Request request) {
    Objects.requireNonNull(request, "request");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World initial placement must verify authority outside an ambient transaction");
    }
    HeldPlacementAuthority held =
        Objects.requireNonNull(
            verifier.verifyAndHold(request),
            "Initial placement verifier returned no held authority");
    try (held) {
      held.requireHeld();
      WorldCanonicalInitialPlayerLocation.Result result = repository.place(request, held);
      held.requireHeld();
      return result;
    }
  }

  private static HeldPlacementAuthority denyByDefault(
      WorldCanonicalInitialPlayerLocation.Request request) {
    throw new PlacementDeniedException(
        "World initial placement has no authenticated current Entity assignment, Account, and Game Session authority producer");
  }

  @FunctionalInterface
  public interface PlacementAuthorityVerifier {
    HeldPlacementAuthority verifyAndHold(WorldCanonicalInitialPlayerLocation.Request request);
  }

  /** Distinct operation-specific current-authority fence held through local commit/readback. */
  public interface HeldPlacementAuthority extends AutoCloseable {
    void requireHeld();

    @Override
    void close();
  }

  public static final class PlacementDeniedException extends IllegalStateException {
    public PlacementDeniedException(String message) {
      super(message);
    }
  }
}
