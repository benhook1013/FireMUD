package net.firedevops.firemud.gamesession.command.text;

import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;

record TextCommandDispatchRequest(
    String sessionId,
    TextCommand command,
    boolean requiresSoloTick,
    Optional<SessionContext> sessionContext) {}
