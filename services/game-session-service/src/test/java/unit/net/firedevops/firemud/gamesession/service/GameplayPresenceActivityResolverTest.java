package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import org.junit.jupiter.api.Test;

class GameplayPresenceActivityResolverTest {

  @Test
  void explicitAfkWinsOverDerivedAutoAfk() {
    PresenceProperties properties = new PresenceProperties();
    properties.setAutoAfkEnabled(true);
    properties.setAutoAfkThresholdMs(100L);
    GameplayPresenceActivityResolver resolver =
        new GameplayPresenceActivityResolver(properties, () -> 500L);

    GameplayPresence presence =
        new GameplayPresence(
            1L,
            22L,
            7L,
            "demo",
            "production",
            2L,
            102L,
            "Ben",
            GameplayPresenceRole.PLAYER,
            100L,
            300L,
            250L,
            250L);

    assertEquals(GameplayPresenceActivityState.EXPLICIT_AFK, resolver.resolve(presence));
  }

  @Test
  void autoAfkUsesLastMeaningfulOrAcceptedActivity() {
    PresenceProperties properties = new PresenceProperties();
    properties.setAutoAfkEnabled(true);
    properties.setAutoAfkThresholdMs(100L);
    AtomicLong now = new AtomicLong(200L);
    GameplayPresenceActivityResolver resolver =
        new GameplayPresenceActivityResolver(properties, now::get);

    GameplayPresence activeFromAccepted =
        new GameplayPresence(
            1L,
            22L,
            7L,
            "demo",
            "production",
            2L,
            102L,
            "Ben",
            GameplayPresenceRole.PLAYER,
            50L,
            null,
            150L,
            null);
    assertEquals(GameplayPresenceActivityState.ACTIVE, resolver.resolve(activeFromAccepted));

    GameplayPresence autoAfk =
        new GameplayPresence(
            1L,
            22L,
            7L,
            "demo",
            "production",
            2L,
            102L,
            "Ben",
            GameplayPresenceRole.PLAYER,
            50L,
            null,
            60L,
            60L);
    assertEquals(GameplayPresenceActivityState.AUTO_AFK, resolver.resolve(autoAfk));
  }
}
