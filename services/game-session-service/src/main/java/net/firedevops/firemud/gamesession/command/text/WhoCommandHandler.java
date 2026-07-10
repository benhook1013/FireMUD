package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WhoCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(WhoCommandHandler.class);
  private final GameplayPresenceService gameplayPresenceService;
  private final GameplayPresenceActivityResolver gameplayPresenceActivityResolver;
  private final ScriptEventPublisher scriptEventPublisher;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected services are stored only for internal command handling")
  public WhoCommandHandler(
      GameplayPresenceService gameplayPresenceService,
      GameplayPresenceActivityResolver gameplayPresenceActivityResolver,
      ScriptEventPublisher scriptEventPublisher) {
    this.gameplayPresenceService = gameplayPresenceService;
    this.gameplayPresenceActivityResolver = gameplayPresenceActivityResolver;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Timed(value = "gamesession.command.who")
  public TextCommandInterpretationResult handle(TextCommand command, SessionContext context) {
    List<GameplayPresence> presences =
        gameplayPresenceService.listConnectedByGameInstance(
            context.tenantId(), context.gameInstanceId());
    WhoViewOutput body = toView(presences);
    publishCommandEvent(context, command);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(), List.of(PlayerOutput.view(body)));
  }

  private void publishCommandEvent(SessionContext context, TextCommand command) {
    try {
      scriptEventPublisher.publishCommandEvent(
          context, ScriptEventGameplayCommands.synthetic("who", command));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Who script event publish failed tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          ex);
    }
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
