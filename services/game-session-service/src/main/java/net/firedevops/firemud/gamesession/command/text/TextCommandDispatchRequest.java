package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;

record TextCommandDispatchRequest(
    String sessionId,
    TextCommand command,
    boolean requiresSoloTick,
    Optional<SessionContext> sessionContext,
    Optional<ObservedDirectTextTransport> observedDirectTextTransport) {
  TextCommandDispatchRequest {
    Objects.requireNonNull(observedDirectTextTransport, "transport observation must not be null");
    observedDirectTextTransport.ifPresent(
        observed -> {
          if (!Objects.equals(sessionId, observed.transportSessionId())) {
            throw new IllegalArgumentException(
                "Observed transport session ID must match dispatch session ID");
          }
        });
  }
}
