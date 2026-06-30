package net.firedevops.firemud.gamesession.command.text;

public interface AuthoredActionRuntimeHandler {
  TextCommandInterpretationResult handle(TextCommand command);
}
