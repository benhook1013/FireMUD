package net.firedevops.firemud.entitymanagement.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class ActorIdentityValueTest {
  private static final UUID ACTOR_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void parsesOnlyCompleteNonNilUuidValues() {
    assertEquals(ACTOR_UUID, ActorIdentity.parseRequiredUuid(ACTOR_UUID.toString(), "actor"));
    assertThrows(
        IllegalArgumentException.class, () -> ActorIdentity.parseRequiredUuid(null, "actor"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ActorIdentity.parseRequiredUuid("not-a-uuid", "actor"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ActorIdentity.parseRequiredUuid("00000000-0000-0000-0000-000000000000", "actor"));
  }

  @Test
  void ownerResolvedIdentityRequiresExactAccountTenantNamespaceAndScope() {
    var identity =
        new ActorIdentity(
            ACTOR_UUID,
            ACCOUNT_UUID,
            TENANT_UUID,
            NAMESPACE_UUID,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            ActorIdentityStatus.OWNER_RESOLVED,
            null);
    assertEquals(ActorIdentityStatus.OWNER_RESOLVED, identity.status());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActorIdentity(
                ACTOR_UUID,
                null,
                TENANT_UUID,
                NAMESPACE_UUID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                ActorIdentityStatus.OWNER_RESOLVED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActorIdentity(
                ACTOR_UUID,
                ACCOUNT_UUID,
                TENANT_UUID,
                NAMESPACE_UUID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED,
                ActorIdentityStatus.OWNER_RESOLVED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActorIdentity(
                ACTOR_UUID,
                ACCOUNT_UUID,
                TENANT_UUID,
                NAMESPACE_UUID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                ActorIdentityStatus.OWNER_RESOLVED,
                "LEGACY_IDENTITY"));
  }

  @Test
  void quarantineCanRetainIncompleteSourceProvenanceWithoutCreatingAnAlias() {
    var quarantined =
        new ActorIdentity(
            ACTOR_UUID,
            null,
            null,
            null,
            null,
            ActorIdentityStatus.QUARANTINED,
            "OWNER_PROVENANCE_MISSING");
    assertEquals(ActorIdentityStatus.QUARANTINED, quarantined.status());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActorIdentity(
                new UUID(0L, 0L),
                null,
                null,
                null,
                null,
                ActorIdentityStatus.QUARANTINED,
                "OWNER_PROVENANCE_MISSING"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActorIdentity(
                ACTOR_UUID, ACCOUNT_UUID, null, null, null, ActorIdentityStatus.QUARANTINED, " "));
  }
}
