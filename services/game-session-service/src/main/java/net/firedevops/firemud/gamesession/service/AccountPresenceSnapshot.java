package net.firedevops.firemud.gamesession.service;

import java.time.Instant;

/**
 * Canonical account-scoped social presence snapshot derived from current gameplay runtime state.
 */
public record AccountPresenceSnapshot(
    long accountId,
    boolean online,
    Long gameInstanceId,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    Long characterId,
    String characterName,
    GameplayPresenceActivityState activityState,
    Instant lastSeenAt,
    AccountRecentPresenceDisposition recentDisposition,
    AccountPresenceVisibilityPolicy visibilityPolicy) {}
