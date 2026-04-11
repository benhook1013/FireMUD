package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.function.LongSupplier;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Derives AFK/activity state from canonical gameplay presence facts plus operator policy. */
@Component
public final class GameplayPresenceActivityResolver {
  private final PresenceProperties presenceProperties;
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public GameplayPresenceActivityResolver(PresenceProperties presenceProperties) {
    this(presenceProperties, System::currentTimeMillis);
  }

  GameplayPresenceActivityResolver(
      PresenceProperties presenceProperties, LongSupplier currentTimeMillisSupplier) {
    this.presenceProperties = Objects.requireNonNull(presenceProperties, "presenceProperties");
    this.currentTimeMillisSupplier =
        Objects.requireNonNull(currentTimeMillisSupplier, "currentTimeMillisSupplier");
  }

  public GameplayPresenceActivityState resolve(GameplayPresence presence) {
    Objects.requireNonNull(presence, "presence");
    if (presence.explicitAfkSinceEpochMs() != null) {
      return GameplayPresenceActivityState.EXPLICIT_AFK;
    }
    if (!presenceProperties.isAutoAfkEnabled() || presenceProperties.getAutoAfkThresholdMs() <= 0) {
      return GameplayPresenceActivityState.ACTIVE;
    }
    long mostRecentActivity =
        presence.lastMeaningfulActivityAtEpochMs() != null
            ? presence.lastMeaningfulActivityAtEpochMs()
            : presence.lastAcceptedCommandAtEpochMs() != null
                ? presence.lastAcceptedCommandAtEpochMs()
                : presence.connectedAtEpochMs();
    long inactivityMs = currentTimeMillisSupplier.getAsLong() - mostRecentActivity;
    return inactivityMs >= presenceProperties.getAutoAfkThresholdMs()
        ? GameplayPresenceActivityState.AUTO_AFK
        : GameplayPresenceActivityState.ACTIVE;
  }
}
