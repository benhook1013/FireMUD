package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.impl.FakeGameplayPresenceService;
import net.firedevops.firemud.gamesession.test.GameplayPresenceTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WhoCommandHandlerTest {
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);
  private final GameplayPresenceActivityResolver activityResolver =
      new GameplayPresenceActivityResolver(new PresenceProperties());
  private final TextPlayerOutputRenderer renderer =
      new TextPlayerOutputRenderer(new PresentationProperties());
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);

  @Test
  void whoShowsBoundedEmptyStateWhenNobodyIsConnected() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService();
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "who"),
            GameplayPresenceTestFixtures.whoBen("second@example.com"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [0]: ");
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.any(),
            Mockito.argThat(
                gameplayCommand ->
                    "WHO".equals(gameplayCommand.getCommandName())
                        && "who".equals(gameplayCommand.getCommandText())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("who-")));
  }

  @Test
  void whoPresentsJwtRoleClaimSessionsAsPlayersWithoutGrantEvidence() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService();
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);
    String godJwt =
        jwtUtil.generateToken(
            net.firedevops.firemud.test.AccountRuntimeStubServer.accountUuidForTestFixture(1L),
            java.util.Map.of(
                "accountId",
                net.firedevops.firemud.test.AccountRuntimeStubServer.accountUuidForTestFixture(1L),
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("god"))));

    gameplayPresenceService.registerConnected(
        GameplayPresenceTestFixtures.whoAster("god@example.com", godJwt));
    String moderatorJwt =
        jwtUtil.generateToken(
            net.firedevops.firemud.test.AccountRuntimeStubServer.accountUuidForTestFixture(4L),
            java.util.Map.of(
                "accountId",
                net.firedevops.firemud.test.AccountRuntimeStubServer.accountUuidForTestFixture(4L),
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator"))));
    gameplayPresenceService.registerConnected(GameplayPresenceTestFixtures.whoDara(moderatorJwt));
    gameplayPresenceService.registerConnected(
        GameplayPresenceTestFixtures.whoBen("second@example.com"));
    gameplayPresenceService.registerConnected(GameplayPresenceTestFixtures.whoCara());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            GameplayPresenceTestFixtures.whoBen("second@example.com"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [4]: Aster, Ben, Cara, Dara");
  }

  @Test
  void normallyJoinedGlobalStaffAppearsAsAnOrdinaryPlayer() {
    for (String role : java.util.List.of("platformAdmin", "support", "billingAdmin", "god")) {
      FakeGameplayPresenceService presence = new FakeGameplayPresenceService();
      String token =
          jwtUtil.generateToken(
              GameplayPresenceTestFixtures.whoAster("staff@example.com", null).accountId(),
              java.util.Map.of(
                  "accountId",
                  GameplayPresenceTestFixtures.whoAster("staff@example.com", null).accountId(),
                  "globalRoles",
                  java.util.List.of(role)));
      var context = GameplayPresenceTestFixtures.whoAster("staff@example.com", token);
      presence.registerConnected(context);
      WhoCommandHandler handler =
          new WhoCommandHandler(presence, activityResolver, scriptEventPublisher);
      TextCommandInterpretationResult result =
          handler.handle(new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"), context);
      assertThat(result.commandResult().accepted()).isTrue();
      assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [1]: Aster");
    }
  }

  @Test
  void whoOmitsRemovedPresenceAfterLogoutLikeCleanup() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService();
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);

    gameplayPresenceService.registerConnected(
        GameplayPresenceTestFixtures.whoAster("first@example.com", null));
    gameplayPresenceService.registerConnected(
        GameplayPresenceTestFixtures.whoBen("second@example.com"));
    gameplayPresenceService.removeBySessionId(1L);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            GameplayPresenceTestFixtures.whoBen("second@example.com"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [1]: Ben");
  }

  @Test
  void whoAnnotatesExplicitAndAutoAfkPlayers() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService();
    GameplayPresenceActivityResolver resolver =
        Mockito.mock(GameplayPresenceActivityResolver.class);
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, resolver, scriptEventPublisher);

    gameplayPresenceService.registerConnected(
        GameplayPresenceTestFixtures.whoAster("active@example.com", null));
    gameplayPresenceService.registerConnected(
        GameplayPresenceTestFixtures.whoBen("idle@example.com"));
    when(resolver.resolve(gameplayPresenceService.findConnectedBySessionId(1L).orElseThrow()))
        .thenReturn(GameplayPresenceActivityState.ACTIVE);
    when(resolver.resolve(gameplayPresenceService.findConnectedBySessionId(2L).orElseThrow()))
        .thenReturn(GameplayPresenceActivityState.EXPLICIT_AFK);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            GameplayPresenceTestFixtures.whoAster("active@example.com", null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output ->
                assertThat(output.payload())
                    .isInstanceOf(
                        net.firedevops.firemud.gamesession.presentation.WhoViewOutput.class));
    assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [2]: Aster, Ben (AFK)");
  }

  private String render(TextCommandInterpretationResult result) {
    return renderer.render(result.outputs().getFirst());
  }
}
