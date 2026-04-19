package net.firedevops.firemud.gamesession.presentation;

import java.util.List;

public record FriendPresenceViewOutput(List<Entry> friends) implements PlayerOutputPayload {
  public FriendPresenceViewOutput {
    friends = List.copyOf(friends);
  }

  public record Entry(
      int ordinal,
      long friendAccountId,
      String displayName,
      boolean online,
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      String characterName,
      String activityState,
      Long lastSeenAtEpochMs,
      String recentDisposition) {}
}
