package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;

/** Original two-phase result only; recovery does not grant a new read or mutation. */
public record AccountGameLogicIntakeSourceReadRecovery(
    GameLogicIntakeSourceReadScope scope,
    State state,
    Optional<GameLogicIntakeAuthorizationBinding> authorization) {
  public enum State {
    RESERVED,
    FINALIZED,
    ABORTED
  }

  public AccountGameLogicIntakeSourceReadRecovery {
    Objects.requireNonNull(scope);
    Objects.requireNonNull(state);
    Objects.requireNonNull(authorization);
    if ((state == State.FINALIZED) != authorization.isPresent())
      throw new IllegalArgumentException("Exact phase result required");
  }
}
