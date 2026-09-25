package net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountJoinDigestTest {
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");

  @Test
  void scopeAndRequestDigestsMatchUtf8CanonicalVectors() {
    VerifiedJoinScope scope = scope("scope-token-α", "production");

    assertEquals(
        "sha256:15efd695569eca4a26589922c7ad128c3a2318203d1a2181666cc3ed6650f5b8",
        AccountJoinDigest.scope(scope));
    assertEquals(
        "sha256:764b03cbe948293517acb4ce201dbd24fc4d2a0a2306e853f2ab0a6c3775a768",
        AccountJoinDigest.request(scope, "bootstrap-jti-α", true, 5L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", true, 5L),
        AccountJoinDigest.request(scope, "bootstrap-jti-α", false, 5L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", true, 5L),
        AccountJoinDigest.request(scope, "bootstrap-jti-α", true, 6L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", true, 5L),
        AccountJoinDigest.request(scope("scope-token-α", "preview"), "bootstrap-jti-α", true, 5L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", true, 5L),
        AccountJoinDigest.request(withGameInstance(scope, 45L), "bootstrap-jti-α", true, 5L));
    assertNotEquals(
        AccountJoinDigest.scope(scope),
        AccountJoinDigest.scope(
            withRealm(scope, UUID.fromString("57c58f36-c5ea-4aa8-8ef7-91a45e407f01"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountJoinDigest.request(scope, "bootstrap-jti-α", null, null));

    assertEquals(
        "sha256:13445ed45c66f0e149250f4101c95452a74f15e56dbf3b9b271852ff2d058776",
        AccountJoinDigest.intent("join-request-α", scope, "bootstrap-jti-α"));
  }

  @Test
  void canonicalSerializationRejectsAmbiguousValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountJoinDigest.scope(scope("scope=token", "production")));
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountJoinDigest.scope(scope("scope-token", "production\nJOIN")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AccountJoinDigest.request(scope("scope-token", "production"), "bad=binding", true, 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountJoinDigest.request(scope("scope-token", "production"), "caller", true, null));
  }

  private static VerifiedJoinScope withGameInstance(VerifiedJoinScope scope, long gameInstanceId) {
    return new VerifiedJoinScope(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        gameInstanceId,
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt(),
        scope.snapshotDigest());
  }

  private static VerifiedJoinScope withRealm(VerifiedJoinScope scope, UUID realmId) {
    return new VerifiedJoinScope(
        scope.connectScopeId(),
        scope.accountId(),
        scope.tenantId(),
        realmId,
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt(),
        scope.snapshotDigest());
  }

  private static VerifiedJoinScope scope(String connectScopeId, String realmSlug) {
    return new VerifiedJoinScope(
        connectScopeId,
        11L,
        7L,
        REALM_ID,
        "demo",
        realmSlug,
        "namespace-44",
        "SHARED",
        44L,
        23L,
        17L,
        "2026-09-24T10:00:00Z",
        "2026-09-24T10:02:00Z",
        "unused-by-preimage");
  }
}
