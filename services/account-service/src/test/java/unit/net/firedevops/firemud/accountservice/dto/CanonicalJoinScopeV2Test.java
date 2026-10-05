package unit.net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import org.junit.jupiter.api.Test;

class CanonicalJoinScopeV2Test {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Test
  void exposesOrderedUuidTypedIdentityAndNoPersistedDigestField() {
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
  void rejectsNilUuidIdentitiesAndNonPositiveBigintCounters() {
    CanonicalJoinScopeV2 scope = scope();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                scope.connectScopeId(),
                NIL_UUID,
                scope.tenantId(),
                scope.realmId(),
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmSlug(),
                scope.playableStateNamespaceId(),
                scope.playableStateScope(),
                scope.gameInstanceId(),
                scope.catalogRevision(),
                scope.pointerVersion(),
                scope.evaluatedAt(),
                scope.connectScopeExpiresAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                scope.connectScopeId(),
                scope.accountId(),
                NIL_UUID,
                scope.realmId(),
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmSlug(),
                scope.playableStateNamespaceId(),
                scope.playableStateScope(),
                scope.gameInstanceId(),
                scope.catalogRevision(),
                scope.pointerVersion(),
                scope.evaluatedAt(),
                scope.connectScopeExpiresAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                scope.connectScopeId(),
                scope.accountId(),
                scope.tenantId(),
                NIL_UUID,
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmSlug(),
                scope.playableStateNamespaceId(),
                scope.playableStateScope(),
                scope.gameInstanceId(),
                scope.catalogRevision(),
                scope.pointerVersion(),
                scope.evaluatedAt(),
                scope.connectScopeExpiresAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                scope.connectScopeId(),
                scope.accountId(),
                scope.tenantId(),
                scope.realmId(),
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmSlug(),
                NIL_UUID,
                scope.playableStateScope(),
                scope.gameInstanceId(),
                scope.catalogRevision(),
                scope.pointerVersion(),
                scope.evaluatedAt(),
                scope.connectScopeExpiresAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                scope.connectScopeId(),
                scope.accountId(),
                scope.tenantId(),
                scope.realmId(),
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmSlug(),
                scope.playableStateNamespaceId(),
                scope.playableStateScope(),
                NIL_UUID,
                scope.catalogRevision(),
                scope.pointerVersion(),
                scope.evaluatedAt(),
                scope.connectScopeExpiresAt()));

    assertThrows(IllegalArgumentException.class, () -> withCounters(scope, 0L, 1L));
    assertThrows(IllegalArgumentException.class, () -> withCounters(scope, 1L, 0L));
    assertThrows(IllegalArgumentException.class, () -> withCounters(scope, -1L, 1L));
    assertThrows(IllegalArgumentException.class, () -> withCounters(scope, 1L, -1L));
  }

  @Test
  void preservesExactCanonicalSelectorAndUtcTimestampBytes() {
    CanonicalJoinScopeV2 exact = scope();
    CanonicalJoinScopeV2 sameInstantDifferentTimestampBytes =
        withEvaluatedAt(exact, "2026-10-03T01:02:03.12Z");

    assertEquals("acme-worlds", exact.tenantSlug());
    assertEquals("demo", exact.worldSlug());
    assertEquals("main", exact.realmSlug());
    assertEquals("2026-10-03T01:02:03.120Z", exact.evaluatedAt());
    assertEquals(
        Instant.parse(exact.evaluatedAt()),
        Instant.parse(sameInstantDifferentTimestampBytes.evaluatedAt()));
    assertEquals("2026-10-03T01:02:03.12Z", sameInstantDifferentTimestampBytes.evaluatedAt());
    assertNotEquals(
        AccountJoinDigest.scopeV2(exact),
        AccountJoinDigest.scopeV2(sameInstantDifferentTimestampBytes));
  }

  @Test
  void preservesExactSelectorBytesAndRejectsMalformedOrNonUtcTimestamps() {
    CanonicalJoinScopeV2 scope = scope();
    CanonicalJoinScopeV2 exactSelectorBytes =
        build(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            "Acme-worlds",
            "demo_world",
            "máin",
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt());

    assertEquals("Acme-worlds", exactSelectorBytes.tenantSlug());
    assertEquals("demo_world", exactSelectorBytes.worldSlug());
    assertEquals("máin", exactSelectorBytes.realmSlug());
    assertNotEquals(
        AccountJoinDigest.scopeV2(scope), AccountJoinDigest.scopeV2(exactSelectorBytes));
    assertThrows(IllegalArgumentException.class, () -> withPlayableStateScope(scope, "shared"));
    assertThrows(
        IllegalArgumentException.class, () -> withEvaluatedAt(scope, "2026-10-03T01:02:03+00:00"));
    assertThrows(
        IllegalArgumentException.class, () -> withEvaluatedAt(scope, "2026-10-03T01:02:03z"));
    assertThrows(
        IllegalArgumentException.class, () -> withEvaluatedAt(scope, "2026-02-30T01:02:03Z"));
    assertThrows(
        IllegalArgumentException.class, () -> withEvaluatedAt(scope, "2026-10-03T24:02:03Z"));
    assertThrows(IllegalArgumentException.class, () -> withExpiry(scope, "2026-10-03T01:04:03"));
    assertThrows(IllegalArgumentException.class, () -> withConnectScopeId(scope, "\uD800"));
    assertThrows(IllegalArgumentException.class, () -> withRealmSlug(scope, "\uD800"));
  }

  private static Class<?> componentType(RecordComponent[] components, String name) {
    return Arrays.stream(components)
        .filter(component -> component.getName().equals(name))
        .findFirst()
        .orElseThrow()
        .getType();
  }

  private static CanonicalJoinScopeV2 scope() {
    return build(
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

  private static CanonicalJoinScopeV2 build(
      String connectScopeId,
      UUID accountId,
      UUID tenantId,
      UUID realmId,
      String tenantSlug,
      String worldSlug,
      String realmSlug,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID gameInstanceId,
      long catalogRevision,
      long pointerVersion,
      String evaluatedAt,
      String connectScopeExpiresAt) {
    return new CanonicalJoinScopeV2(
        connectScopeId,
        accountId,
        tenantId,
        realmId,
        tenantSlug,
        worldSlug,
        realmSlug,
        playableStateNamespaceId,
        playableStateScope,
        gameInstanceId,
        catalogRevision,
        pointerVersion,
        evaluatedAt,
        connectScopeExpiresAt);
  }

  private static CanonicalJoinScopeV2 withCounters(
      CanonicalJoinScopeV2 scope, long catalogRevision, long pointerVersion) {
    return build(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        catalogRevision,
        pointerVersion,
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withConnectScopeId(
      CanonicalJoinScopeV2 scope, String connectScopeId) {
    return build(
        connectScopeId,
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withRealmSlug(CanonicalJoinScopeV2 scope, String realmSlug) {
    return build(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        realmSlug,
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withPlayableStateScope(
      CanonicalJoinScopeV2 scope, String playableStateScope) {
    return build(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        playableStateScope,
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withEvaluatedAt(
      CanonicalJoinScopeV2 scope, String evaluatedAt) {
    return build(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        evaluatedAt,
        scope.connectScopeExpiresAt());
  }

  private static CanonicalJoinScopeV2 withExpiry(CanonicalJoinScopeV2 scope, String expiresAt) {
    return build(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        expiresAt);
  }
}
