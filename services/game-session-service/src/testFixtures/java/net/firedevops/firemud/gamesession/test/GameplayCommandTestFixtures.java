package net.firedevops.firemud.gamesession.test;

import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.service.SessionContext;

/** Named command scenarios shared by handler, dispatch, and durable-execution tests. */
public final class GameplayCommandTestFixtures {
  private GameplayCommandTestFixtures() {}

  public static SessionContext inventoryEmberline() {
    return new SessionContext(
        1L, 22L, "123", "emberline@example.com", 911L, "Emberline", 77L, "R-7", "jwt-token");
  }

  public static SessionContext authoredDispatchEmberline() {
    return new SessionContext(
        7L, 22L, "41", "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");
  }

  public static SessionContext historyEmberline() {
    return new SessionContext(
        22L, 7L, "99", "demo@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");
  }

  public static SessionContext durableDemo() {
    return new SessionContext(
        42L, 22L, "7", "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
  }

  public static SessionContext authoredHelpEmberline() {
    return new SessionContext(
        41L, 22L, "123", "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt");
  }

  public static SessionContext helpCapabilityEmberline() {
    return helpCapabilityEmberline(42L);
  }

  public static SessionContext helpCapabilityEmberline(long sessionId) {
    return new SessionContext(
        sessionId, 22L, "123", "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");
  }

  public static SessionContext settingsEmber() {
    return new SessionContext(1L, 22L, "123", "demo@example.com", 911L, "Ember", 7L, "R-1", null);
  }

  public static SessionContext unnamedOutputActor() {
    return new SessionContext(41L, 22L, "123", 123L, 1L, "R-1", "jwt");
  }

  public static SessionContext communicationSenderEmberline() {
    return new SessionContext(
        41L, 22L, "123", "demo@example.com", 123L, "Emberline", 1L, "R-1", "jwt");
  }

  public static SessionContext communicationTargetSora() {
    return new SessionContext(
        42L, 22L, "456", "friend@example.com", 456L, "Sora", 1L, "R-2", "jwt", "en-NZ", 1L);
  }

  public static SessionContext worldBrowseDemo() {
    return new SessionContext(1L, 22L, "123", "demo@example.com", 0L, null, 0L, "jwt");
  }

  public static SessionContext bootstrapDemo() {
    return new SessionContext(
        41L, 22L, "123", "demo@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
  }

  public static SessionContext webSocketEmberline() {
    return new SessionContext(
        41L, 22L, "123", "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt", "en-NZ", 1L);
  }

  public static GameplayCommand durableDemoCommand(String commandName, String commandText) {
    SessionContext actor = durableDemo();
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-1");
    command.setTenantId(actor.tenantId());
    command.setGameInstanceId(actor.gameInstanceId());
    command.setSessionId(actor.sessionId());
    command.setCharacterId(actor.characterId());
    command.setCommandName(commandName);
    command.setCommandText(commandText);
    command.setSanitizedCommandText(commandText);
    return command;
  }
}
