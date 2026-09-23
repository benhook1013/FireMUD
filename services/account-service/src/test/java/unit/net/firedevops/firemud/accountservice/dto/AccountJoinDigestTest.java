package net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AccountJoinDigestTest {
  @Test
  void scopeAndRequestDigestsMatchUtf8CanonicalVectors() {
    VerifiedJoinScope scope = scope("scope-token-α", "production");

    assertEquals(
        "sha256:3ecdb49d051fb18e9e925031da8245504af61d956993b5b44fc7b4f0781b0da6",
        AccountJoinDigest.scope(scope));
    assertEquals(
        "sha256:764b03cbe948293517acb4ce201dbd24fc4d2a0a2306e853f2ab0a6c3775a768",
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "AVAILABLE", true, 5L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "AVAILABLE", true, 5L),
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "AVAILABLE", false, 5L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "AVAILABLE", true, 5L),
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "AVAILABLE", true, 6L));
    assertNotEquals(
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "AVAILABLE", true, 5L),
        AccountJoinDigest.request(
            scope("scope-token-α", "preview"), "bootstrap-jti-α", "AVAILABLE", true, 5L));
    assertEquals(
        "sha256:3450f964af0d11503e93a34d5178697de8c07c0eb7fe7e215d70de61a93f5a38",
        AccountJoinDigest.request(scope, "bootstrap-jti-α", "UNAVAILABLE", null, null));
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
            AccountJoinDigest.request(
                scope("scope-token", "production"), "bad=binding", "AVAILABLE", true, 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AccountJoinDigest.request(
                scope("scope-token", "production"), "caller", "AVAILABLE", true, null));
  }

  private static VerifiedJoinScope scope(String connectScopeId, String realmSlug) {
    return new VerifiedJoinScope(
        connectScopeId,
        11L,
        7L,
        31L,
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
