package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Structured payload for canonical friend add/remove mutation results. */
public record FriendMutationResultOutput(
    String action, long friendAccountId, String displayName, String characterName, Integer ordinal)
    implements PlayerOutputPayload {
  public FriendMutationResultOutput {
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(displayName, "displayName must not be null");
  }
}
