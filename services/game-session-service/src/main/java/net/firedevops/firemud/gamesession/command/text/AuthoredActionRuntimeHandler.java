package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamesession.service.SessionContext;

public interface AuthoredActionRuntimeHandler {
  TextCommandInterpretationResult handle(TextCommand command);

  default TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    return handle(command);
  }
}
