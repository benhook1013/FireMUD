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
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.FakeGameplayPresenceService;
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
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "who"),
            new SessionContext(
                2L,
                22L,
                "22222222-2222-4222-8222-222222222222",
                "second@example.com",
                102L,
                "Ben",
                7L,
                "R-1",
                null));

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
  void whoGroupsElevatedPlayersFirstAndPlayersAfterward() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);
    String godJwt =
        jwtUtil.generateToken(
            "11111111-1111-4111-8111-111111111111",
            java.util.Map.of(
                "accountId",
                "11111111-1111-4111-8111-111111111111",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("god"))));

    gameplayPresenceService.registerConnected(
        new SessionContext(
            1L,
            22L,
            "11111111-1111-4111-8111-111111111111",
            "god@example.com",
            101L,
            "Aster",
            7L,
            "R-1",
            godJwt));
    String moderatorJwt =
        jwtUtil.generateToken(
            "89ce29d0-212d-4769-892a-67299d123e17",
            java.util.Map.of(
                "accountId",
                "89ce29d0-212d-4769-892a-67299d123e17",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator"))));
    gameplayPresenceService.registerConnected(
        new SessionContext(
            4L,
            22L,
            "89ce29d0-212d-4769-892a-67299d123e17",
            "moderator@example.com",
            104L,
            "Dara",
            7L,
            "R-1",
            moderatorJwt));
    gameplayPresenceService.registerConnected(
        new SessionContext(
            2L,
            22L,
            "22222222-2222-4222-8222-222222222222",
            "second@example.com",
            102L,
            "Ben",
            7L,
            "R-1",
            null));
    gameplayPresenceService.registerConnected(
        new SessionContext(
            3L,
            22L,
            "33333333-3333-4333-8333-333333333333",
            "third@example.com",
            103L,
            "Cara",
            7L,
            "R-1",
            null));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            new SessionContext(
                2L,
                22L,
                "22222222-2222-4222-8222-222222222222",
                "second@example.com",
                102L,
                "Ben",
                7L,
                "R-1",
                null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(render(result)).isEqualTo("Gods [2]: Aster, Dara\nPlayers [2]: Ben, Cara");
  }

  @Test
  void whoShowsGlobalOnlyRolesAsPlayers() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);
    String globalOnlyJwt =
        jwtUtil.generateToken(
            "c91fb96e-5ad8-4e4e-a12d-2838640093b2",
            java.util.Map.of(
                "accountId",
                "c91fb96e-5ad8-4e4e-a12d-2838640093b2",
                "globalRoles",
                java.util.List.of("platformAdmin", "support", "billingAdmin", "god", "moderator")));

    gameplayPresenceService.registerConnected(
        new SessionContext(
            1L,
            22L,
            "c91fb96e-5ad8-4e4e-a12d-2838640093b2",
            "staff@example.com",
            101L,
            "Aster",
            7L,
            "R-1",
            globalOnlyJwt));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            new SessionContext(
                2L,
                22L,
                "5f7624ca-8aee-4d34-9cd8-3ba3a1815f30",
                "second@example.com",
                102L,
                "Ben",
                7L,
                "R-1",
                null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [1]: Aster");
  }

  @Test
  void whoOmitsRemovedPresenceAfterLogoutLikeCleanup() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, activityResolver, scriptEventPublisher);

    gameplayPresenceService.registerConnected(
        new SessionContext(
            1L,
            22L,
            "11111111-1111-4111-8111-111111111111",
            "first@example.com",
            101L,
            "Aster",
            7L,
            "R-1",
            null));
    gameplayPresenceService.registerConnected(
        new SessionContext(
            2L,
            22L,
            "22222222-2222-4222-8222-222222222222",
            "second@example.com",
            102L,
            "Ben",
            7L,
            "R-1",
            null));
    gameplayPresenceService.removeBySessionId(1L);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            new SessionContext(
                2L,
                22L,
                "22222222-2222-4222-8222-222222222222",
                "second@example.com",
                102L,
                "Ben",
                7L,
                "R-1",
                null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(render(result)).isEqualTo("Gods [0]: \nPlayers [1]: Ben");
  }

  @Test
  void whoAnnotatesExplicitAndAutoAfkPlayers() {
    FakeGameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService(jwtUtil);
    GameplayPresenceActivityResolver resolver =
        Mockito.mock(GameplayPresenceActivityResolver.class);
    WhoCommandHandler handler =
        new WhoCommandHandler(gameplayPresenceService, resolver, scriptEventPublisher);

    gameplayPresenceService.registerConnected(
        new SessionContext(
            1L,
            22L,
            "11111111-1111-4111-8111-111111111111",
            "active@example.com",
            101L,
            "Aster",
            7L,
            "R-1",
            null));
    gameplayPresenceService.registerConnected(
        new SessionContext(
            2L,
            22L,
            "22222222-2222-4222-8222-222222222222",
            "idle@example.com",
            102L,
            "Ben",
            7L,
            "R-1",
            null));
    when(resolver.resolve(gameplayPresenceService.findConnectedBySessionId(1L).orElseThrow()))
        .thenReturn(GameplayPresenceActivityState.ACTIVE);
    when(resolver.resolve(gameplayPresenceService.findConnectedBySessionId(2L).orElseThrow()))
        .thenReturn(GameplayPresenceActivityState.EXPLICIT_AFK);

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.WHO, java.util.List.of(), "WHO"),
            new SessionContext(
                1L,
                22L,
                "11111111-1111-4111-8111-111111111111",
                "active@example.com",
                101L,
                "Aster",
                7L,
                "R-1",
                null));

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
