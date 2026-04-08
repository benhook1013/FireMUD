package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
public class WhoCommandHandler {
  private final GameplayPresenceService gameplayPresenceService;

  public WhoCommandHandler(GameplayPresenceService gameplayPresenceService) {
    this.gameplayPresenceService = gameplayPresenceService;
  }

  @Timed(value = "gamesession.command.who")
  public TextCommandInterpretationResult handle(SessionContext context) {
    List<GameplayPresence> presences =
        gameplayPresenceService.listConnectedByGameInstance(
            context.tenantId(), context.gameInstanceId());
    String gods =
        presences.stream()
            .filter(presence -> presence.role() == GameplayPresenceRole.GOD)
            .map(GameplayPresence::characterName)
            .collect(Collectors.joining(", "));
    String players =
        presences.stream()
            .filter(presence -> presence.role() == GameplayPresenceRole.PLAYER)
            .map(GameplayPresence::characterName)
            .collect(Collectors.joining(", "));
    String body =
        "Gods ["
            + count(presences, GameplayPresenceRole.GOD)
            + "]: "
            + gods
            + "\nPlayers ["
            + count(presences, GameplayPresenceRole.PLAYER)
            + "]: "
            + players;
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(), List.of(PlayerOutput.notice(body)));
  }

  private long count(List<GameplayPresence> presences, GameplayPresenceRole role) {
    return presences.stream().filter(presence -> presence.role() == role).count();
  }
}
