package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/** Parsed unsigned scope carried by the authenticated, allowlisted gameplay workload. */
public record CanonicalGameplayRosterExecutionContext(
    UUID accountUuid,
    UUID tenantUuid,
    UUID playableStateNamespaceUuid,
    UUID gameInstanceUuid,
    UUID characterUuid,
    UUID sessionUuid,
    UUID realmUuid,
    UUID requestUuid,
    PlayableStateScope playableStateScope) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalGameplayRosterExecutionContext {
    requireNonNil(accountUuid, "accountUuid");
    requireNonNil(tenantUuid, "tenantUuid");
    requireNonNil(playableStateNamespaceUuid, "playableStateNamespaceUuid");
    requireNonNil(gameInstanceUuid, "gameInstanceUuid");
    if (characterUuid != null) {
      requireNonNil(characterUuid, "characterUuid");
    }
    requireNonNil(sessionUuid, "sessionUuid");
    requireNonNil(realmUuid, "realmUuid");
    requireNonNil(requestUuid, "requestUuid");
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    if (playableStateScope == PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        || playableStateScope == PlayableStateScope.UNRECOGNIZED) {
      throw new IllegalArgumentException("playableStateScope is required");
    }
  }

  public void requireTargetBinding(
      UUID expectedRequestUuid, UUID expectedAccountUuid, CanonicalGameplayRosterTarget target) {
    Objects.requireNonNull(target, "target");
    if (!requestUuid.equals(expectedRequestUuid)
        || !accountUuid.equals(expectedAccountUuid)
        || !tenantUuid.equals(target.tenantUuid())
        || !realmUuid.equals(target.realmUuid())
        || !playableStateNamespaceUuid.equals(target.playableStateNamespaceId())
        || !gameInstanceUuid.equals(target.gameInstanceUuid())
        || playableStateScope != target.playableStateScope()) {
      throw new IllegalArgumentException("Player execution context does not match exact target");
    }
  }

  private static void requireNonNil(UUID value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be non-nil");
    }
  }
}
