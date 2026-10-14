package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;

/** Inactive exact-original adapter holding external reset/restore exclusion through retirement. */
public final class InitialGameplayLoginRedisAttemptClosure
    implements InitialGameplayLoginAttemptClosure {
  private final InitialGameplayLoginContextRedisClient client;

  public InitialGameplayLoginRedisAttemptClosure(InitialGameplayLoginContextRedisClient client) {
    this.client = Objects.requireNonNull(client);
  }

  @Override
  public <T> Optional<T> withAcknowledgedClosure(
      InitialGameplayLoginAttemptCoordinates coordinates,
      Function<AcknowledgedClosure, T> continuation) {
    Objects.requireNonNull(coordinates);
    Objects.requireNonNull(continuation);
    var acquired = client.acquireQualification();
    if (acquired.isEmpty()) return Optional.empty();
    try (var held = acquired.orElseThrow()) {
      if (client.closeOriginal(coordinates, held).isEmpty()) return Optional.empty();
      held.requireCurrent();
      // The external lease remains held during the callback, including its Account dispatch.
      T result = continuation.apply(new AcknowledgedClosure(coordinates));
      held.requireCurrent();
      return Optional.ofNullable(result);
    }
  }
}
