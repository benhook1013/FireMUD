package net.firedevops.firemud.gamesession.test;

import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;

/** Concrete WHO roster subjects and the separate activity-resolution scenario. */
public final class GameplayPresenceTestFixtures {
  private GameplayPresenceTestFixtures() {}

  public static SessionContext whoBen(String loginName) {
    return new SessionContext(2L, 22L, "2", loginName, 102L, "Ben", 7L, "R-1", null);
  }

  public static SessionContext whoAster(String loginName, String jwt) {
    return new SessionContext(1L, 22L, "1", loginName, 101L, "Aster", 7L, "R-1", jwt);
  }

  public static SessionContext whoCara() {
    return new SessionContext(3L, 22L, "3", "third@example.com", 103L, "Cara", 7L, "R-1", null);
  }

  public static SessionContext whoDara(String jwt) {
    return new SessionContext(4L, 22L, "4", "moderator@example.com", 104L, "Dara", 7L, "R-1", jwt);
  }

  public static GameplayPresence activityBen(
      long connectedAt,
      Long explicitAfkSince,
      Long lastAcceptedCommandAt,
      Long lastMeaningfulActivityAt) {
    SessionContext ben = whoBen("second@example.com");
    return new GameplayPresence(
        1L,
        ben.tenantId(),
        ben.gameInstanceId(),
        "demo",
        "production",
        ben.accountId(),
        ben.characterId(),
        ben.characterName(),
        GameplayPresenceRole.PLAYER,
        connectedAt,
        explicitAfkSince,
        lastAcceptedCommandAt,
        lastMeaningfulActivityAt);
  }
}
