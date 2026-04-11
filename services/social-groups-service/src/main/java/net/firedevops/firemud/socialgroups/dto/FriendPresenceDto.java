package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record FriendPresenceDto(
    Long friendAccountId,
    boolean online,
    Long gameInstanceId,
    Long characterId,
    String characterName,
    FriendPresenceActivityState activityState,
    Instant lastSeenAt) {}
