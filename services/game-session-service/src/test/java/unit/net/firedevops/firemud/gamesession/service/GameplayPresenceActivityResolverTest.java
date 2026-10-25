package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.test.GameplayPresenceTestFixtures;
import org.junit.jupiter.api.Test;

class GameplayPresenceActivityResolverTest {

  @Test
  void explicitAfkWinsOverDerivedAutoAfk() {
    PresenceProperties properties = new PresenceProperties();
    properties.setAutoAfkEnabled(true);
    properties.setAutoAfkThresholdMs(100L);
    GameplayPresenceActivityResolver resolver =
        new GameplayPresenceActivityResolver(properties, () -> 500L);

    GameplayPresence presence = GameplayPresenceTestFixtures.activityBen(100L, 300L, 250L, 250L);

    assertEquals(GameplayPresenceActivityState.EXPLICIT_AFK, resolver.resolve(presence));
  }

  @Test
  void autoAfkIgnoresAcceptedNonGameplayActivity() {
    PresenceProperties properties = new PresenceProperties();
    properties.setAutoAfkEnabled(true);
    properties.setAutoAfkThresholdMs(100L);
    AtomicLong now = new AtomicLong(200L);
    GameplayPresenceActivityResolver resolver =
        new GameplayPresenceActivityResolver(properties, now::get);

    GameplayPresence acceptedOnly = GameplayPresenceTestFixtures.activityBen(50L, null, 150L, null);
    assertEquals(GameplayPresenceActivityState.AUTO_AFK, resolver.resolve(acceptedOnly));

    GameplayPresence activeFromMeaningful =
        GameplayPresenceTestFixtures.activityBen(50L, null, 60L, 150L);
    assertEquals(GameplayPresenceActivityState.ACTIVE, resolver.resolve(activeFromMeaningful));

    GameplayPresence autoAfk = GameplayPresenceTestFixtures.activityBen(50L, null, 60L, 60L);
    assertEquals(GameplayPresenceActivityState.AUTO_AFK, resolver.resolve(autoAfk));
  }

  @Test
  void autoAfkFallsBackToConnectionTimeWhenNoGameplayActivityExists() {
    PresenceProperties properties = new PresenceProperties();
    properties.setAutoAfkEnabled(true);
    properties.setAutoAfkThresholdMs(100L);
    GameplayPresenceActivityResolver resolver =
        new GameplayPresenceActivityResolver(properties, () -> 200L);

    GameplayPresence presence = GameplayPresenceTestFixtures.activityBen(150L, null, 190L, null);

    assertEquals(GameplayPresenceActivityState.ACTIVE, resolver.resolve(presence));
  }
}
