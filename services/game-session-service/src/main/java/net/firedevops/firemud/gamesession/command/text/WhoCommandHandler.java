package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.WhoViewOutput;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
public class WhoCommandHandler {
  private final GameplayPresenceService gameplayPresenceService;
  private final GameplayPresenceActivityResolver gameplayPresenceActivityResolver;

  public WhoCommandHandler(
      GameplayPresenceService gameplayPresenceService,
      GameplayPresenceActivityResolver gameplayPresenceActivityResolver) {
    this.gameplayPresenceService = gameplayPresenceService;
    this.gameplayPresenceActivityResolver = gameplayPresenceActivityResolver;
  }

  @Timed(value = "gamesession.command.who")
  public TextCommandInterpretationResult handle(SessionContext context) {
    List<GameplayPresence> presences =
        gameplayPresenceService.listConnectedByGameInstance(
            context.tenantId(), context.gameInstanceId());
    WhoViewOutput body = toView(presences);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(), List.of(PlayerOutput.view(body)));
  }

  private WhoViewOutput toView(List<GameplayPresence> presences) {
    ArrayList<WhoViewOutput.Entry> gods = new ArrayList<>();
    ArrayList<WhoViewOutput.Entry> players = new ArrayList<>();
    int godOrdinal = 1;
    int playerOrdinal = 1;
    for (GameplayPresence presence : presences) {
      WhoViewOutput.Entry entry =
          new WhoViewOutput.Entry(
              presence.role() == GameplayPresenceRole.GOD ? godOrdinal++ : playerOrdinal++,
              presence.characterName(),
              gameplayPresenceActivityResolver.resolve(presence).name());
      if (presence.role() == GameplayPresenceRole.GOD) {
        gods.add(entry);
      } else {
        players.add(entry);
      }
    }
    return new WhoViewOutput(gods, players);
  }
}
