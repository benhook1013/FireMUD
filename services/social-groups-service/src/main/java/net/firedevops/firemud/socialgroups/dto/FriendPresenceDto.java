package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record FriendPresenceDto(
    Long friendAccountId,
    boolean online,
    Long gameInstanceId,
    String playableStateScope,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    Long pointerVersion,
    Long characterId,
    String characterName,
    String visibilityPolicy,
    FriendPresenceActivityState activityState,
    Instant lastSeenAt,
    FriendRecentPresenceDisposition recentDisposition) {
  public FriendPresenceDto(
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
      FriendRecentPresenceDisposition recentDisposition) {
    this(
        friendAccountId,
        online,
        gameInstanceId,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        characterId,
        characterName,
        null,
        activityState,
        lastSeenAt,
        recentDisposition);
  }

  public FriendPresenceDto(
      Long friendAccountId,
      boolean online,
      Long gameInstanceId,
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      Long characterId,
      String characterName,
      String visibilityPolicy,
      FriendPresenceActivityState activityState,
      Instant lastSeenAt,
      FriendRecentPresenceDisposition recentDisposition) {
    this(
        friendAccountId,
        online,
        gameInstanceId,
        null,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        null,
        characterId,
        characterName,
        visibilityPolicy,
        activityState,
        lastSeenAt,
        recentDisposition);
  }

  public FriendPresenceDto(
      Long friendAccountId,
      boolean online,
      Long gameInstanceId,
      String playableStateScope,
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      Long pointerVersion,
      Long characterId,
      String characterName,
      FriendPresenceActivityState activityState,
      Instant lastSeenAt,
      FriendRecentPresenceDisposition recentDisposition) {
    this(
        friendAccountId,
        online,
        gameInstanceId,
        playableStateScope,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        pointerVersion,
        characterId,
        characterName,
        null,
        activityState,
        lastSeenAt,
        recentDisposition);
  }

  public FriendPresenceDto(
      Long friendAccountId,
      boolean online,
      Long gameInstanceId,
      String playableStateScope,
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      Long characterId,
      String characterName,
      String visibilityPolicy,
      FriendPresenceActivityState activityState,
      Instant lastSeenAt,
      FriendRecentPresenceDisposition recentDisposition) {
    this(
        friendAccountId,
        online,
        gameInstanceId,
        playableStateScope,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        null,
        characterId,
        characterName,
        visibilityPolicy,
        activityState,
        lastSeenAt,
        recentDisposition);
  }
}
