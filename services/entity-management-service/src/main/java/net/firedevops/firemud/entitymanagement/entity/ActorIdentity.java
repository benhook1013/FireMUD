package net.firedevops.firemud.entitymanagement.entity;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.util.StringUtils;

/** Canonical Entity-owned actor identity and the provenance disposition that gates its use. */
public record ActorIdentity(
    UUID characterUuid,
    UUID accountUuid,
    UUID tenantUuid,
    UUID playableStateNamespaceId,
    PlayableStateScope playableStateScope,
    ActorIdentityStatus status,
    String quarantineReason) {

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  public ActorIdentity {
    requireNonNil(characterUuid, "characterUuid");
    requireNonNilWhenPresent(accountUuid, "accountUuid");
    requireNonNilWhenPresent(tenantUuid, "tenantUuid");
    requireNonNilWhenPresent(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(status, "status");
    if (playableStateScope != null) {
      requireScope(playableStateScope);
    }
    if (status == ActorIdentityStatus.OWNER_RESOLVED) {
      requireNonNil(accountUuid, "accountUuid");
      requireNonNil(tenantUuid, "tenantUuid");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      requireScope(playableStateScope);
      if (StringUtils.hasText(quarantineReason)) {
        throw new IllegalArgumentException(
            "owner-resolved identity cannot have a quarantine reason");
      }
    } else if (!StringUtils.hasText(quarantineReason)) {
      throw new IllegalArgumentException("quarantined identity requires a reason");
    }
  }

  public static UUID parseRequiredUuid(String value, String fieldName) {
    if (!StringUtils.hasText(value) || !value.matches(UUID_PATTERN)) {
      throw new IllegalArgumentException(fieldName + " must be a canonical UUID");
    }
    UUID parsed = UUID.fromString(value);
    requireNonNil(parsed, fieldName);
    return parsed;
  }

  private static void requireNonNil(UUID value, String fieldName) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be a non-nil UUID");
    }
  }

  private static void requireNonNilWhenPresent(UUID value, String fieldName) {
    if (value != null && NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must not be the nil UUID");
    }
  }

  public static PlayableStateScope requireScope(PlayableStateScope scope) {
    if (scope == null
        || (scope != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
            && scope != PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)) {
      throw new IllegalArgumentException("playableStateScope must be owner-resolved");
    }
    return scope;
  }
}
