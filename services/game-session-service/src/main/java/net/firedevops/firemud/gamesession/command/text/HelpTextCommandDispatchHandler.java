package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class HelpTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final HelpCommandHandler helpHandler;

  HelpTextCommandDispatchHandler(HelpCommandHandler helpHandler) {
    this.helpHandler = helpHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.HELP;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return helpHandler.handle(request.command());
  }
}
