package unit.net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import org.junit.jupiter.api.Test;

class CanonicalJoinScopeV2Test {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Test
  void exposesCanonicalUuidTypedScopeWithoutDigestOrAuthorizationField() {
    RecordComponent[] components = CanonicalJoinScopeV2.class.getRecordComponents();

    assertArrayEquals(
        new String[] {
          "connectScopeId",
          "accountId",
          "tenantId",
          "realmId",
          "tenantSlug",
          "worldSlug",
          "realmSlug",
          "playableStateNamespaceId",
          "playableStateScope",
          "gameInstanceId",
          "catalogRevision",
          "pointerVersion",
          "evaluatedAt",
          "connectScopeExpiresAt"
        },
        Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new));
    assertEquals(UUID.class, componentType(components, "accountId"));
    assertEquals(UUID.class, componentType(components, "tenantId"));
    assertEquals(UUID.class, componentType(components, "realmId"));
    assertEquals(UUID.class, componentType(components, "playableStateNamespaceId"));
    assertEquals(UUID.class, componentType(components, "gameInstanceId"));
  }

  @Test
  void rejectsNilIdentityInvalidCountersAndMalformedScopeStrings() {
    CanonicalJoinScopeV2 scope = scope();
    assertThrows(IllegalArgumentException.class, () -> withAccount(scope, NIL_UUID));
    assertThrows(IllegalArgumentException.class, () -> withTenant(scope, NIL_UUID));
    assertThrows(IllegalArgumentException.class, () -> withRealm(scope, NIL_UUID));
    assertThrows(IllegalArgumentException.class, () -> withNamespace(scope, NIL_UUID));
    assertThrows(IllegalArgumentException.class, () -> withGameInstance(scope, NIL_UUID));
    assertThrows(IllegalArgumentException.class, () -> withCounters(scope, 0L, 1L));
    assertThrows(IllegalArgumentException.class, () -> withCounters(scope, 1L, 0L));
    assertThrows(IllegalArgumentException.class, () -> withPlayableScope(scope, "shared"));
    assertThrows(
        IllegalArgumentException.class, () -> withEvaluatedAt(scope, "2026-02-30T00:00:00Z"));
    assertThrows(
        IllegalArgumentException.class, () -> withExpiry(scope, "2026-10-03T00:01:00+00:00"));
    assertThrows(IllegalArgumentException.class, () -> withConnectScopeId(scope, "\uD800"));
    assertThrows(IllegalArgumentException.class, () -> withWorldSlug(scope, "\uD800"));
  }

  @Test
  void preservesExactSelectorAndUtcTimestampBytesInDigestInput() {
    CanonicalJoinScopeV2 scope = scope();
    CanonicalJoinScopeV2 alternateTimestamp = withEvaluatedAt(scope, "2026-10-03T01:02:03.12Z");
    CanonicalJoinScopeV2 alternateSelector = withRealmSlug(scope, "Máin");

    assertEquals("2026-10-03T01:02:03.120Z", scope.evaluatedAt());
    assertNotEquals(
        AccountJoinDigest.scopeV2(scope), AccountJoinDigest.scopeV2(alternateTimestamp));
    assertNotEquals(AccountJoinDigest.scopeV2(scope), AccountJoinDigest.scopeV2(alternateSelector));
  }

  private static Class<?> componentType(RecordComponent[] components, String name) {
    return Arrays.stream(components)
        .filter(component -> component.getName().equals(name))
        .findFirst()
        .orElseThrow()
        .getType();
  }

  private static CanonicalJoinScopeV2 scope() {
    return new CanonicalJoinScopeV2(
        "connect-scope-42",
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        "acme-worlds",
        "demo",
        "main",
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "SHARED",
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        "2026-10-03T01:02:03.120Z",
        "2026-10-03T01:04:03Z");
  }

  private static CanonicalJoinScopeV2 copy(
      CanonicalJoinScopeV2 scope,
      String connectScopeId,
      UUID accountId,
      UUID tenantId,
      UUID realmId,
      String tenantSlug,
      String worldSlug,
      String realmSlug,
      UUID namespaceId,
      String playableScope,
      UUID gameInstanceId,
      long catalogRevision,
      long pointerVersion,
      String evaluatedAt,
      String expiresAt) {
    return new CanonicalJoinScopeV2(
        connectScopeId,
        accountId,
        tenantId,
        realmId,
        tenantSlug,
        worldSlug,
        realmSlug,
        namespaceId,
        playableScope,
        gameInstanceId,
        catalogRevision,
        pointerVersion,
        evaluatedAt,
        expiresAt);
  }

  private static CanonicalJoinScopeV2 withAccount(CanonicalJoinScopeV2 s, UUID v) {
    return copy(
        s,
        s.connectScopeId(),
        v,
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withTenant(CanonicalJoinScopeV2 s, UUID v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        v,
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withRealm(CanonicalJoinScopeV2 s, UUID v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        v,
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withNamespace(CanonicalJoinScopeV2 s, UUID v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        v,
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withGameInstance(CanonicalJoinScopeV2 s, UUID v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        v,
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withCounters(
      CanonicalJoinScopeV2 s, long catalog, long pointer) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        catalog,
        pointer,
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withPlayableScope(CanonicalJoinScopeV2 s, String v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        v,
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withEvaluatedAt(CanonicalJoinScopeV2 s, String v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        v,
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withExpiry(CanonicalJoinScopeV2 s, String v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        v);
  }

  private static CanonicalJoinScopeV2 withConnectScopeId(CanonicalJoinScopeV2 s, String v) {
    return copy(
        s,
        v,
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withWorldSlug(CanonicalJoinScopeV2 s, String v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        v,
        s.realmSlug(),
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withRealmSlug(CanonicalJoinScopeV2 s, String v) {
    return copy(
        s,
        s.connectScopeId(),
        s.accountId(),
        s.tenantId(),
        s.realmId(),
        s.tenantSlug(),
        s.worldSlug(),
        v,
        s.playableStateNamespaceId(),
        s.playableStateScope(),
        s.gameInstanceId(),
        s.catalogRevision(),
        s.pointerVersion(),
        s.evaluatedAt(),
        s.connectScopeExpiresAt());
  }
}
