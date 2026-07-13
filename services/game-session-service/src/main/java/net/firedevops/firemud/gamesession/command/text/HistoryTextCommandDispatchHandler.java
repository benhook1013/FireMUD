package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class HistoryTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final HistoryCommandHandler historyHandler;

  HistoryTextCommandDispatchHandler(HistoryCommandHandler historyHandler) {
    this.historyHandler = historyHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.HISTORY;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    return historyHandler.handle(request.command(), request.sessionContext().orElseThrow());
  }
}
