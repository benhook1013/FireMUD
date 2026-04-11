package net.firedevops.firemud.gamesession.service;

/**
 * Canonical account-scoped social presence snapshot derived from current gameplay runtime state.
 */
public record AccountPresenceSnapshot(
    long accountId,
    boolean online,
    Long gameInstanceId,
    Long characterId,
    String characterName,
    GameplayPresenceActivityState activityState) {}
