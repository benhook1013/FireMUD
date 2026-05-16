package net.firedevops.firemud.gamesession.presentation;

import java.util.List;

public record FriendPresenceViewOutput(
    String filter, int totalCount, int matchCount, List<Entry> friends)
    implements PlayerOutputPayload {
  public FriendPresenceViewOutput {
    friends = List.copyOf(friends);
  }

  public record Entry(
      int ordinal,
      Long friendLinkId,
      long friendAccountId,
      String status,
      Long linkedAtEpochMs,
      String displayName,
      boolean online,
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      String characterName,
      String playableStateScope,
      Long pointerVersion,
      String activityState,
      Long lastSeenAtEpochMs,
      String recentDisposition,
      String visibilityPolicy) {}
}
