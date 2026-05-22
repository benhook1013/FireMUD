package net.firedevops.firemud.gamesession.presentation;

public record FriendRosterSummaryViewOutput(
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
    int unspecifiedScopeCount)
    implements PlayerOutputPayload {}
