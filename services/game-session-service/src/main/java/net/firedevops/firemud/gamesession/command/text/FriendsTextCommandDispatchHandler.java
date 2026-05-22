package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

@Component
final class FriendsTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final FriendsCommandHandler friendsHandler;

  FriendsTextCommandDispatchHandler(FriendsCommandHandler friendsHandler) {
    this.friendsHandler = friendsHandler;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.FRIENDS;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    return friendsHandler.handle(request.command(), request.sessionContext().orElseThrow());
  }
}
