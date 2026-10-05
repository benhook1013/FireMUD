package net.firedevops.firemud.gamesession.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record FriendPresenceViewOutput(String filter, int totalCount, List<Entry> friends)
    implements PlayerOutputPayload {
  public FriendPresenceViewOutput {
    friends = List.copyOf(friends);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Entry(
      int ordinal,
      Long friendLinkId,
      String friendAccountId,
      String status,
      Long linkedAtEpochMs,
      String displayName,
      Boolean online,
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      String characterName,
      String playableStateScope,
      Long pointerVersion,
      String activityState,
      Long lastSeenAtEpochMs,
      String visibilityPolicy) {}
}
