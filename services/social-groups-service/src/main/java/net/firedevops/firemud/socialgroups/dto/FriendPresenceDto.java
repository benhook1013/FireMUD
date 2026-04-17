package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record FriendPresenceDto(
    Long friendAccountId,
    boolean online,
    Long gameInstanceId,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    Long characterId,
    String characterName,
    FriendPresenceActivityState activityState,
    Instant lastSeenAt,
    FriendRecentPresenceDisposition recentDisposition) {}
