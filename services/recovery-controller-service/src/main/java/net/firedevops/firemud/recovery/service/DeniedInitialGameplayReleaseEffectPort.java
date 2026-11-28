package net.firedevops.firemud.recovery.service;

import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TypedEffect;

/**
 * Safe default until the Game Session typed effect and current-readback owner path is registered.
 */
public class DeniedInitialGameplayReleaseEffectPort implements InitialGameplayReleaseEffectPort {
  @Override
  public EffectApplyResult apply(TypedEffect effect, UUID attemptId, int attemptOrdinal) {
    return EffectApplyResult.notDispatched("GAME_SESSION_EFFECT_OWNER_UNAVAILABLE");
  }

  @Override
  public EffectReadbackResolution readback(TypedEffect effect, UUID attemptId, int attemptOrdinal) {
    return EffectReadbackResolution.unavailable();
  }
}
