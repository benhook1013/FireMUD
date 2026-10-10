package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerBootstrapJwtProfileValidatorTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";

  @Test
  void requiresDeclaredEmptyTenantAndRoleShapesButAllowsOptionalGlobalRoles() {
    Map<String, Object> claims = validClaims();

    assertThatCode(() -> PlayerBootstrapJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();
    claims.put("globalRoles", List.of());
    assertThatCode(() -> PlayerBootstrapJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAnyTenantScopeBillingCutoffOrPrivateGrant() {
    Map<String, Object> scoped = validClaims();
    scoped.put("scopedRoles", Map.of("22222222-2222-4222-8222-222222222222", List.of("reader")));
    assertInvalid(scoped);

    Map<String, Object> billing = validClaims();
    Map<String, Object> tuple = object(billing.get("authorityTuple"));
    tuple.put("tenantBillingCutoff", Map.of("22222222-2222-4222-8222-222222222222", Map.of()));
    billing.put("authorityTuple", tuple);
    assertInvalid(billing);

    Map<String, Object> grants = validClaims();
    tuple = object(grants.get("authorityTuple"));
    tuple.put("privateRealmGrantVersions", List.of(Map.of("tenantId", "bad")));
    grants.put("authorityTuple", tuple);
    assertInvalid(grants);
  }

  private static Map<String, Object> validClaims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", ACCOUNT);
    claims.put("accountId", ACCOUNT);
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("aud", PlayerBootstrapJwtProfileValidator.AUDIENCE);
    claims.put("iat", 1L);
    claims.put("nbf", 1L);
    claims.put("exp", 300L);
    claims.put("tokenGeneration", 1L);
    claims.put("issuanceFence", 1L);
    claims.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 1L,
            "tenantAuthorityGeneration", Map.of(),
            "membershipAuthorityGeneration", Map.of(),
            "privateRealmGrantVersions", List.of()));
    claims.put("membershipVersion", Map.of());
    claims.put("scopedRoles", Map.of());
    return claims;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return new LinkedHashMap<>((Map<String, Object>) value);
  }

  private static void assertInvalid(Map<String, Object> claims) {
    assertThatThrownBy(() -> PlayerBootstrapJwtProfileValidator.validateClaims(claims))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasNoCause();
  }
}
