package net.firedevops.firemud.gamesession.command.text;

interface TextCommandDispatchHandler {
  TextCommandDispatchGroup group();

  TextCommandInterpretationResult handle(TextCommandDispatchRequest request);
}
