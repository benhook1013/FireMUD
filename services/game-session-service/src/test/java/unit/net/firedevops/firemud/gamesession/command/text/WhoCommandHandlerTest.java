package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.InMemoryGameplayPresenceService;
import org.junit.jupiter.api.Test;

class WhoCommandHandlerTest {
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);

  @Test
  void whoShowsBoundedEmptyStateWhenNobodyIsConnected() {
    InMemoryGameplayPresenceService gameplayPresenceService =
        new InMemoryGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler = new WhoCommandHandler(gameplayPresenceService);

    TextCommandInterpretationResult result =
        handler.handle(
            new SessionContext(2L, 22L, 2L, "second@example.com", 102L, "Ben", 7L, "R-1", null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(output -> assertThat(output.text()).isEqualTo("Gods [0]: \nPlayers [0]: "));
  }

  @Test
  void whoGroupsGodsFirstAndPlayersAfterward() {
    InMemoryGameplayPresenceService gameplayPresenceService =
        new InMemoryGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler = new WhoCommandHandler(gameplayPresenceService);
    String godJwt =
        jwtUtil.generateToken(
            "1",
            java.util.Map.of(
                "accountId",
                "1",
                "globalRoles",
                java.util.List.of("platformAdmin"),
                "scopedRoles",
                java.util.Map.of()));

    gameplayPresenceService.registerConnected(
        new SessionContext(1L, 22L, 1L, "god@example.com", 101L, "Aster", 7L, "R-1", godJwt));
    gameplayPresenceService.registerConnected(
        new SessionContext(2L, 22L, 2L, "second@example.com", 102L, "Ben", 7L, "R-1", null));
    gameplayPresenceService.registerConnected(
        new SessionContext(3L, 22L, 3L, "third@example.com", 103L, "Cara", 7L, "R-1", null));

    TextCommandInterpretationResult result =
        handler.handle(
            new SessionContext(2L, 22L, 2L, "second@example.com", 102L, "Ben", 7L, "R-1", null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output ->
                assertThat(output.text()).isEqualTo("Gods [1]: Aster\nPlayers [2]: Ben, Cara"));
  }

  @Test
  void whoOmitsRemovedPresenceAfterLogoutLikeCleanup() {
    InMemoryGameplayPresenceService gameplayPresenceService =
        new InMemoryGameplayPresenceService(jwtUtil);
    WhoCommandHandler handler = new WhoCommandHandler(gameplayPresenceService);

    gameplayPresenceService.registerConnected(
        new SessionContext(1L, 22L, 1L, "first@example.com", 101L, "Aster", 7L, "R-1", null));
    gameplayPresenceService.registerConnected(
        new SessionContext(2L, 22L, 2L, "second@example.com", 102L, "Ben", 7L, "R-1", null));
    gameplayPresenceService.removeBySessionId(1L);

    TextCommandInterpretationResult result =
        handler.handle(
            new SessionContext(2L, 22L, 2L, "second@example.com", 102L, "Ben", 7L, "R-1", null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(output -> assertThat(output.text()).isEqualTo("Gods [0]: \nPlayers [1]: Ben"));
  }
}
