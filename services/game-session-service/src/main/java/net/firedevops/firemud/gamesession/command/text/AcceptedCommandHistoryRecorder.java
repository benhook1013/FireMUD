package net.firedevops.firemud.gamesession.command.text;

import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionContext;

/** Records accepted, safe player commands after command dispatch has settled session identity. */
@FunctionalInterface
public interface AcceptedCommandHistoryRecorder {
  AcceptedCommandHistoryRecorder NOOP =
      (command, commandResult, contextBefore, contextAfter) -> {
        // Used by focused interpreter seams that do not exercise durable history.
      };

  void record(
      TextCommand command,
      CommandEnqueueResult commandResult,
      Optional<SessionContext> contextBefore,
      Optional<SessionContext> contextAfter);
}
