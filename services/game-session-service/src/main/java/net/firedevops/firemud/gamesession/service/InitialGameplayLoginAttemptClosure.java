package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;

/**
 * Source boundary for the live Redis owner; no implementation or runtime registration is supplied.
 * After durable abandonment, close and independently read back ONLY the exact original context and
 * attempt fence. A positive result requires acknowledged exclusion of every delayed compare-only
 * installation for that original, including already dispatched mutations, and detachment of its
 * account context. Never create an absent/replaced context, rehydrate a closed attempt, reuse a
 * context UUID, or mutate a newer attempt. Bare absence, changed context, timeout, lost ACK,
 * unavailable Redis or reset/restore uncertainty are empty/exceptional outcomes. A positive result
 * is valid only inside the adapter's qualified coordination availability boundary; retained SQL
 * evidence and an old closure receipt cannot supply that qualification on a later invocation.
 */
public interface InitialGameplayLoginAttemptClosure {
  /**
   * Invoke the continuation only after exact ACK/readback and keep its closure qualification valid
   * through that continuation's retirement dispatch. Empty means held. Do not retry or invoke the
   * continuation twice in one invocation. SQL transactions inside the continuation end before the
   * remote Account call; the adapter must not wrap the continuation in a SQL transaction.
   */
  <T> Optional<T> withAcknowledgedClosure(
      InitialGameplayLoginAttemptCoordinates coordinates,
      Function<AcknowledgedClosure, T> continuation);

  /** The adapter supplies this only after the exact close ACK and independent readback above. */
  record AcknowledgedClosure(InitialGameplayLoginAttemptCoordinates coordinates) {
    public AcknowledgedClosure {
      Objects.requireNonNull(coordinates, "coordinates");
    }
  }
}
