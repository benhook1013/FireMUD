package net.firedevops.firemud.socialgroups.dto;

public record FriendRosterSummaryDto(
    int totalCount,
    int onlineCount,
    int offlineCount,
    int recentCount,
    int publicCount,
    int friendsOnlyCount,
    int privateCount,
    int hiddenStaffCount,
    int unspecifiedVisibilityCount,
    int sharedCount,
    int isolatedCount,
    int unspecifiedScopeCount) {}
