package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

public record FriendDetailViewOutput(FriendPresenceViewOutput.Entry friend)
    implements PlayerOutputPayload {
  public FriendDetailViewOutput {
    Objects.requireNonNull(friend, "friend must not be null");
  }
}
