package net.firedevops.firemud.gamesession.test;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class GameplayCommandTestFixturesTest {
  @Test
  void inventoryScenarioRetainsItsIndependentSubjectAndRuntimeTarget() {
    SessionContext context = GameplayCommandTestFixtures.inventoryEmberline();

    assertThat(context.sessionId()).isEqualTo(1L);
    assertThat(context.tenantId()).isEqualTo(22L);
    assertThat(context.accountId()).isEqualTo("123");
    assertThat(context.characterId()).isEqualTo(911L);
    assertThat(context.gameInstanceId()).isEqualTo(77L);
    assertThat(context.roomInstanceId()).isEqualTo("R-7");
    assertThat(context.characterName()).isEqualTo("Emberline");
  }

  @Test
  void authoredDispatchAndHistoryKeepTheirDistinctSubjects() {
    SessionContext dispatch = GameplayCommandTestFixtures.authoredDispatchEmberline();
    SessionContext history = GameplayCommandTestFixtures.historyEmberline();

    assertThat(dispatch.accountId()).isEqualTo("41");
    assertThat(dispatch.sessionId()).isEqualTo(7L);
    assertThat(dispatch.tenantId()).isEqualTo(22L);
    assertThat(history.accountId()).isEqualTo("99");
    assertThat(history.sessionId()).isEqualTo(22L);
    assertThat(history.tenantId()).isEqualTo(7L);
    assertThat(dispatch.characterId()).isEqualTo(7001L);
    assertThat(history.characterId()).isEqualTo(7001L);
    assertThat(dispatch.gameInstanceId()).isEqualTo(9L);
    assertThat(history.gameInstanceId()).isEqualTo(9L);
  }

  @Test
  void durableCommandAndLiveContextShareTheExactActorBinding() {
    SessionContext context = GameplayCommandTestFixtures.durableDemo();
    var command = GameplayCommandTestFixtures.durableDemoCommand("MOVE", "north");

    assertThat(context.accountId()).isEqualTo("7");
    assertThat(context.characterId()).isEqualTo(91L);
    assertThat(context.sessionId()).isEqualTo(42L);
    assertThat(context.tenantId()).isEqualTo(22L);
    assertThat(context.gameInstanceId()).isEqualTo(5L);
    assertThat(command.getCharacterId()).isEqualTo(context.characterId());
    assertThat(command.getSessionId()).isEqualTo(context.sessionId());
    assertThat(command.getTenantId()).isEqualTo(context.tenantId());
    assertThat(command.getGameInstanceId()).isEqualTo(context.gameInstanceId());
    assertThat(command.getCommandId()).isEqualTo("cmd-1");
    assertThat(command.getCommandName()).isEqualTo("MOVE");
    assertThat(command.getCommandText()).isEqualTo("north");
    assertThat(command.getSanitizedCommandText()).isEqualTo("north");
  }

  @Test
  void negativeCaseMutationDoesNotAlterTheNextDurableCommand() {
    var changed = GameplayCommandTestFixtures.durableDemoCommand("LOOK", "LOOK");
    changed.setCharacterId(null);
    changed.setSessionId(43L);

    var fresh = GameplayCommandTestFixtures.durableDemoCommand("LOOK", "LOOK");

    assertThat(fresh.getCharacterId()).isEqualTo(91L);
    assertThat(fresh.getSessionId()).isEqualTo(42L);
    assertThat(GameplayCommandTestFixtures.durableDemo().characterId()).isEqualTo(91L);
  }

  @Test
  void helpSettingsAndOutputScenariosRetainTheirDifferentBindings() {
    SessionContext help = GameplayCommandTestFixtures.authoredHelpEmberline();
    SessionContext capability = GameplayCommandTestFixtures.helpCapabilityEmberline();
    SessionContext settings = GameplayCommandTestFixtures.settingsEmber();
    SessionContext output = GameplayCommandTestFixtures.unnamedOutputActor();

    assertThat(help.sessionId()).isEqualTo(41L);
    assertThat(help.characterId()).isEqualTo(7001L);
    assertThat(help.gameInstanceId()).isEqualTo(7L);
    assertThat(capability.sessionId()).isEqualTo(42L);
    assertThat(capability.characterId()).isEqualTo(7001L);
    assertThat(capability.gameInstanceId()).isEqualTo(9L);
    assertThat(settings.characterId()).isEqualTo(911L);
    assertThat(settings.characterName()).isEqualTo("Ember");
    assertThat(settings.jwt()).isNull();
    assertThat(output.characterId()).isEqualTo(123L);
    assertThat(output.characterName()).isNull();
    assertThat(output.gameInstanceId()).isEqualTo(1L);
  }

  @Test
  void communicationTargetsAndBootstrapContextsKeepTheirOriginalShapes() {
    SessionContext sender = GameplayCommandTestFixtures.communicationSenderEmberline();
    SessionContext target = GameplayCommandTestFixtures.communicationTargetSora();
    SessionContext bootstrap = GameplayCommandTestFixtures.bootstrapDemo();
    SessionContext browse = GameplayCommandTestFixtures.worldBrowseDemo();
    SessionContext played = GameplayCommandTestFixtures.webSocketEmberline();

    assertThat(sender.accountId()).isEqualTo("123");
    assertThat(sender.characterId()).isEqualTo(123L);
    assertThat(sender.roomInstanceId()).isEqualTo("R-1");
    assertThat(target.accountId()).isEqualTo("456");
    assertThat(target.characterId()).isEqualTo(456L);
    assertThat(target.roomInstanceId()).isEqualTo("R-2");
    assertThat(target.localeTag()).isEqualTo("en-NZ");
    assertThat(bootstrap.sessionId()).isEqualTo(41L);
    assertThat(bootstrap.characterId()).isZero();
    assertThat(bootstrap.gameInstanceId()).isZero();
    assertThat(bootstrap.bootstrapGameInstanceId()).isEqualTo(1L);
    assertThat(browse.characterId()).isZero();
    assertThat(browse.loginName()).isEqualTo("demo@example.com");
    assertThat(played.characterId()).isEqualTo(7001L);
    assertThat(played.localeTag()).isEqualTo("en-NZ");
    assertThat(played.bootstrapGameInstanceId()).isEqualTo(1L);
  }

  @Test
  void whoAndActivitySubjectsKeepDistinctSessionAndActorCoordinates() {
    SessionContext ben = GameplayPresenceTestFixtures.whoBen("idle@example.com");
    SessionContext aster = GameplayPresenceTestFixtures.whoAster("god@example.com", "admin-jwt");
    SessionContext cara = GameplayPresenceTestFixtures.whoCara();
    SessionContext dara = GameplayPresenceTestFixtures.whoDara("moderator-jwt");
    var activity = GameplayPresenceTestFixtures.activityBen(100L, 300L, 250L, 200L);

    assertThat(ben.sessionId()).isEqualTo(2L);
    assertThat(ben.characterId()).isEqualTo(102L);
    assertThat(ben.loginName()).isEqualTo("idle@example.com");
    assertThat(aster.characterId()).isEqualTo(101L);
    assertThat(aster.jwt()).isEqualTo("admin-jwt");
    assertThat(cara.characterId()).isEqualTo(103L);
    assertThat(dara.characterId()).isEqualTo(104L);
    assertThat(dara.jwt()).isEqualTo("moderator-jwt");
    assertThat(activity.sessionId()).isEqualTo(1L);
    assertThat(activity.accountId()).isEqualTo("2");
    assertThat(activity.characterId()).isEqualTo(102L);
    assertThat(activity.connectedAtEpochMs()).isEqualTo(100L);
    assertThat(activity.explicitAfkSinceEpochMs()).isEqualTo(300L);
    assertThat(activity.lastAcceptedCommandAtEpochMs()).isEqualTo(250L);
    assertThat(activity.lastMeaningfulActivityAtEpochMs()).isEqualTo(200L);
  }

  @Test
  void retainedHistoryScopesAndEntriesKeepCursorAndOtherActorsSeparate() {
    var scope = GameplayHistoryTestFixtures.retainedPlayerScope();
    var next = GameplayHistoryTestFixtures.nextRetainedPlayerScope();
    var cursor = GameplayHistoryTestFixtures.retentionCursor();
    var entry = GameplayHistoryTestFixtures.retainedCommand("LOOK", null);

    assertThat(scope.tenantId()).isEqualTo(22L);
    assertThat(scope.gameInstanceId()).isEqualTo(7L);
    assertThat(scope.characterId()).isEqualTo(13L);
    assertThat(next.characterId()).isEqualTo(14L);
    assertThat(cursor.tenantId()).isEqualTo(21L);
    assertThat(cursor.characterId()).isEqualTo(12L);
    assertThat(entry.getTenantId()).isEqualTo(scope.tenantId());
    assertThat(entry.getGameInstanceId()).isEqualTo(scope.gameInstanceId());
    assertThat(entry.getCharacterId()).isEqualTo(scope.characterId());
    assertThat(entry.getCommandText()).isEqualTo("LOOK");
  }
}
