package net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest.EntitlementAvailabilityV2;
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

  @Test
  void v2ScopeAndIntentMatchIndependentGoldenVectorsForAsciiAndUtf8Bindings() {
    CanonicalJoinScopeV2 scope = canonicalScope();

    assertEquals(
        "sha256:f1a0f168078ccedfba6c418648e1ff9d7dcf80d7df8a87016a81e0ccdb9ec312",
        AccountJoinDigest.scopeV2(scope));
    assertEquals(
        "sha256:f1a0f168078ccedfba6c418648e1ff9d7dcf80d7df8a87016a81e0ccdb9ec312",
        referenceDigest("account-join-scope/v2", "PUBLIC_PRODUCTION", List.of(), scope, List.of()));
    assertEquals(
        "sha256:8e550967de5a3b9e4db453fda8b8ae8f9dcf0b82bfe2d747e7513a5435554b97",
        AccountJoinDigest.intentV2("request-42", scope, "caller-binding-42"));
    assertEquals(
        "sha256:8e550967de5a3b9e4db453fda8b8ae8f9dcf0b82bfe2d747e7513a5435554b97",
        referenceDigest(
            "account-join-intent/v2",
            "JOIN",
            List.of("request-42", "caller-binding-42"),
            scope,
            List.of()));

    assertEquals(
        "sha256:8f3d27ab24423eb49e8f7b7f85110457f10a470003a1a7dde514b27b88deb606",
        AccountJoinDigest.intentV2("request-42", scope, "bootstrap-jti-α"));
    assertEquals(
        "sha256:8f3d27ab24423eb49e8f7b7f85110457f10a470003a1a7dde514b27b88deb606",
        referenceDigest(
            "account-join-intent/v2",
            "JOIN",
            List.of("request-42", "bootstrap-jti-α"),
            scope,
            List.of()));
  }

  @Test
  void v2RequestMatchesLargeBigintPolicyVectorAndPolicyChangesChangeDigest() {
    CanonicalJoinScopeV2 scope = canonicalScope();
    String original =
        AccountJoinDigest.requestV2(
            scope, "bootstrap-jti-α", EntitlementAvailabilityV2.AVAILABLE, true, 9L);

    assertEquals(
        "sha256:90dd727af35a10f3c0532383cec9d4813654fd4b903bc551eb99af5ffbe8614f", original);
    assertEquals(
        "sha256:90dd727af35a10f3c0532383cec9d4813654fd4b903bc551eb99af5ffbe8614f",
        referenceDigest(
            "account-join-request/v2",
            "JOIN",
            List.of("bootstrap-jti-α"),
            scope,
            List.of("AVAILABLE", "true", "9")));
    assertNotEquals(
        original,
        AccountJoinDigest.requestV2(
            scope, "bootstrap-jti-α", EntitlementAvailabilityV2.AVAILABLE, false, 9L));
    assertNotEquals(
        original,
        AccountJoinDigest.requestV2(
            scope, "bootstrap-jti-α", EntitlementAvailabilityV2.AVAILABLE, true, 10L));
  }

  @Test
  void everyV2ScopeFieldChangesScopeIntentAndRequestDigests() {
    CanonicalJoinScopeV2 scope = canonicalScope();
    String scopeDigest = AccountJoinDigest.scopeV2(scope);
    String intentDigest = AccountJoinDigest.intentV2("request-42", scope, "caller-binding-42");
    String requestDigest =
        AccountJoinDigest.requestV2(
            scope, "caller-binding-42", EntitlementAvailabilityV2.AVAILABLE, true, 9L);

    for (CanonicalJoinScopeV2 changed : everyScopeFieldChanged(scope)) {
      assertNotEquals(scopeDigest, AccountJoinDigest.scopeV2(changed));
      assertNotEquals(
          intentDigest, AccountJoinDigest.intentV2("request-42", changed, "caller-binding-42"));
      assertNotEquals(
          requestDigest,
          AccountJoinDigest.requestV2(
              changed, "caller-binding-42", EntitlementAvailabilityV2.AVAILABLE, true, 9L));
    }
    assertNotEquals(
        intentDigest, AccountJoinDigest.intentV2("request-43", scope, "caller-binding-42"));
    assertNotEquals(
        intentDigest, AccountJoinDigest.intentV2("request-42", scope, "caller-binding-43"));
    assertNotEquals(
        requestDigest,
        AccountJoinDigest.requestV2(
            scope, "caller-binding-43", EntitlementAvailabilityV2.AVAILABLE, true, 9L));
  }

  @Test
  void v2RequestRejectsUnavailableMissingAndInvalidPolicyEvidence() {
    CanonicalJoinScopeV2 scope = canonicalScope();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            AccountJoinDigest.requestV2(
                scope, "caller", EntitlementAvailabilityV2.UNAVAILABLE, true, 9L));
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountJoinDigest.requestV2(scope, "caller", null, true, 9L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AccountJoinDigest.requestV2(
                scope, "caller", EntitlementAvailabilityV2.AVAILABLE, null, 9L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AccountJoinDigest.requestV2(
                scope, "caller", EntitlementAvailabilityV2.AVAILABLE, true, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AccountJoinDigest.requestV2(
                scope, "caller", EntitlementAvailabilityV2.AVAILABLE, true, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountJoinDigest.intentV2("request", scope, "\uD800"));
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

  private static CanonicalJoinScopeV2 canonicalScope() {
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

  private static List<CanonicalJoinScopeV2> everyScopeFieldChanged(CanonicalJoinScopeV2 scope) {
    return List.of(
        new CanonicalJoinScopeV2(
            "connect-scope-43",
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
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
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
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
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
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            "other-tenant",
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            "other-world",
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            "other-realm",
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            "ISOLATED",
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
            scope.connectScopeId(),
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
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
            scope.catalogRevision() - 1L,
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
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
            scope.pointerVersion() - 1L,
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
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
            "2026-10-03T01:02:03.12Z",
            scope.connectScopeExpiresAt()),
        new CanonicalJoinScopeV2(
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
            "2026-10-03T01:04:04Z"));
  }

  private static String referenceDigest(
      String domain,
      String operationKind,
      List<String> leadingValues,
      CanonicalJoinScopeV2 scope,
      List<String> trailingValues) {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    referenceSegment(preimage, domain);
    referenceSegment(preimage, "2");
    referenceSegment(preimage, operationKind);
    leadingValues.forEach(value -> referenceSegment(preimage, value));
    referenceSegment(preimage, scope.connectScopeId());
    referenceSegment(preimage, scope.accountId().toString());
    referenceSegment(preimage, scope.tenantId().toString());
    referenceSegment(preimage, scope.realmId().toString());
    referenceSegment(preimage, scope.tenantSlug());
    referenceSegment(preimage, scope.worldSlug());
    referenceSegment(preimage, scope.realmSlug());
    referenceSegment(preimage, scope.playableStateNamespaceId().toString());
    referenceSegment(preimage, scope.playableStateScope());
    referenceSegment(preimage, scope.gameInstanceId().toString());
    referenceSegment(preimage, Long.toString(scope.catalogRevision()));
    referenceSegment(preimage, Long.toString(scope.pointerVersion()));
    referenceSegment(preimage, scope.evaluatedAt());
    referenceSegment(preimage, scope.connectScopeExpiresAt());
    trailingValues.forEach(value -> referenceSegment(preimage, value));
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void referenceSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }
}
