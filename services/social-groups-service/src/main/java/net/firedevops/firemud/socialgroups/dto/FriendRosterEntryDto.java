package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record FriendRosterEntryDto(
    int ordinal,
    Long friendLinkId,
    Long tenantId,
    String accountId,
    String friendAccountId,
    String status,
    Instant createdAt,
    FriendPresenceDto presence) {}
