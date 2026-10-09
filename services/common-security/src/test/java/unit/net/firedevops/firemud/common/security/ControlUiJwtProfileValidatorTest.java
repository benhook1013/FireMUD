package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlUiJwtProfileValidatorTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String TENANT_A = "22222222-2222-4222-8222-222222222222";
  private static final String TENANT_B = "33333333-3333-4333-8333-333333333333";

  @Test
  void acceptsSeparateTargetAndMembershipApplicabilityAndOptionalGlobalRoles() {
    Map<String, Object> claims = baseClaims();
    claims.put(
        "authorityTuple", authorityTuple(Map.of(TENANT_A, 2L), Map.of(TENANT_B, 3L), List.of()));
    claims.put("membershipVersion", Map.of(TENANT_B, 8L));
    claims.put("scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin")));

    assertThatCode(() -> ControlUiJwtProfileValidator.validateClaims(claims, 1))
        .doesNotThrowAnyException();
    claims.put("globalRoles", List.of());
    assertThatCode(() -> ControlUiJwtProfileValidator.validateClaims(claims, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void preservesProfileSpecificOptionalAccountCutoffOmission() {
    Map<String, Object> claims = validClaims();
    Map<String, Object> tuple = object(claims.get("authorityTuple"));
    tuple.put("accountAuthorityGeneration", 2L);
    claims.put("authorityTuple", tuple);

    assertThatCode(() -> ControlUiJwtProfileValidator.validateClaims(claims, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMismatchedMembershipPairMalformedScopeAndEmptyPresentOptionalObject() {
    Map<String, Object> mismatch = validClaims();
    mismatch.put("membershipVersion", Map.of(TENANT_A, 8L));
    assertInvalid(mismatch);

    Map<String, Object> badScope = validClaims();
    badScope.put("scopedRoles", Map.of(ACCOUNT, List.of("tenantAdmin")));
    assertInvalid(badScope);

    Map<String, Object> emptyBillingObject = validClaims();
    Map<String, Object> tuple = object(emptyBillingObject.get("authorityTuple"));
    tuple.put("tenantBillingCutoff", Map.of());
    emptyBillingObject.put("authorityTuple", tuple);
    assertInvalid(emptyBillingObject);

    Map<String, Object> nullBillingObject = validClaims();
    tuple = object(nullBillingObject.get("authorityTuple"));
    tuple.put("tenantBillingCutoff", null);
    nullBillingObject.put("authorityTuple", tuple);
    assertInvalid(nullBillingObject);

    Map<String, Object> malformedRole = validClaims();
    malformedRole.put("scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin", "tenantAdmin")));
    assertInvalid(malformedRole);
    assertInvalid(Map.<String, Object>of("unexpected", true));
  }

  @Test
  void sharedConfiguredScopeCapBoundsEveryControlUiApplicabilityMap() {
    Map<String, Object> tooManyScopedRoles = validClaims();
    tooManyScopedRoles.put(
        "scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin"), TENANT_B, List.of("reader")));
    assertInvalidAtCap(tooManyScopedRoles, 1);

    Map<String, Object> tooManyTenantGenerations = validClaims();
    Map<String, Object> tenantTuple = object(tooManyTenantGenerations.get("authorityTuple"));
    tenantTuple.put("tenantAuthorityGeneration", Map.of(TENANT_A, 2L, TENANT_B, 3L));
    tooManyTenantGenerations.put("authorityTuple", tenantTuple);
    tooManyTenantGenerations.put(
        "scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin"), TENANT_B, List.of("reader")));
    assertInvalidAtCap(tooManyTenantGenerations, 1);

    Map<String, Object> tooManyMembershipGenerations = validClaims();
    Map<String, Object> membershipTuple =
        object(tooManyMembershipGenerations.get("authorityTuple"));
    membershipTuple.put("membershipAuthorityGeneration", Map.of(TENANT_A, 2L, TENANT_B, 3L));
    tooManyMembershipGenerations.put("authorityTuple", membershipTuple);
    tooManyMembershipGenerations.put("membershipVersion", Map.of(TENANT_A, 7L, TENANT_B, 8L));
    tooManyMembershipGenerations.put("scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin")));
    assertInvalidAtCap(tooManyMembershipGenerations, 1);

    Map<String, Object> tooManyBillingCutoffs = validClaims();
    Map<String, Object> billingTuple = object(tooManyBillingCutoffs.get("authorityTuple"));
    Map<String, Object> cutoff =
        Map.of(
            "tenantAuthorityGeneration", 2L,
            "tenantBillingSequence", 3L,
            "outboxStreamKey", "account:tenant-billing:v1:tenant/" + TENANT_A,
            "outboxSequence", 4L);
    billingTuple.put("tenantBillingCutoff", Map.of(TENANT_A, cutoff, TENANT_B, cutoff));
    tooManyBillingCutoffs.put("authorityTuple", billingTuple);
    tooManyBillingCutoffs.put(
        "scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin"), TENANT_B, List.of("reader")));
    assertInvalidAtCap(tooManyBillingCutoffs, 1);
  }

  @Test
  void eachControlUiApplicabilityMapAcceptsTheExactConfiguredCap() {
    Map<String, Object> claims = validClaims();
    Map<String, Object> tuple = object(claims.get("authorityTuple"));
    tuple.put(
        "tenantBillingCutoff",
        Map.of(
            TENANT_A,
            Map.of(
                "tenantAuthorityGeneration",
                2L,
                "tenantBillingSequence",
                3L,
                "outboxStreamKey",
                "account:tenant-billing:v1:tenant/" + TENANT_A,
                "outboxSequence",
                4L)));
    claims.put("authorityTuple", tuple);

    assertThatCode(() -> ControlUiJwtProfileValidator.validateClaims(claims, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void nilAndAccountIdTenantKeysAreRejectedAsMalformedProfileKeys() {
    Map<String, Object> nilAccount = validClaims();
    nilAccount.put("sub", "00000000-0000-0000-0000-000000000000");
    nilAccount.put("accountId", "00000000-0000-0000-0000-000000000000");
    assertInvalid(nilAccount);

    Map<String, Object> accountKey = validClaims();
    Map<String, Object> tuple = object(accountKey.get("authorityTuple"));
    tuple.put("tenantAuthorityGeneration", Map.of(ACCOUNT, 2L));
    accountKey.put("authorityTuple", tuple);
    accountKey.put("scopedRoles", Map.of(ACCOUNT, List.of("tenantAdmin")));
    assertInvalid(accountKey);
  }

  @Test
  void canonicalAuthorityTupleByteCeilingIsEnforcedAfterTheFiniteCountCap() {
    Map<String, Long> tenantGenerations = new LinkedHashMap<>();
    Map<String, List<String>> scopedRoles = new LinkedHashMap<>();
    for (int index = 0; index < 100; index++) {
      String tenantId = UUID.randomUUID().toString();
      tenantGenerations.put(tenantId, 1L);
      scopedRoles.put(tenantId, List.of("reader"));
    }
    Map<String, Object> claims = baseClaims();
    claims.put("authorityTuple", authorityTuple(tenantGenerations, Map.of(), List.of()));
    claims.put("membershipVersion", Map.of());
    claims.put("scopedRoles", scopedRoles);

    assertInvalidAtCap(claims, 128);
  }

  private static Map<String, Object> validClaims() {
    Map<String, Object> claims = baseClaims();
    claims.put(
        "authorityTuple", authorityTuple(Map.of(TENANT_A, 2L), Map.of(TENANT_B, 3L), List.of()));
    claims.put("membershipVersion", Map.of(TENANT_B, 8L));
    claims.put("scopedRoles", Map.of(TENANT_A, List.of("tenantAdmin")));
    return claims;
  }

  private static Map<String, Object> baseClaims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", ACCOUNT);
    claims.put("accountId", ACCOUNT);
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("aud", ControlUiJwtProfileValidator.AUDIENCE);
    claims.put("iat", 1L);
    claims.put("nbf", 1L);
    claims.put("exp", 300L);
    claims.put("tokenGeneration", 1L);
    claims.put("issuanceFence", 1L);
    return claims;
  }

  private static Map<String, Object> authorityTuple(
      Map<String, Long> tenantGenerations,
      Map<String, Long> membershipGenerations,
      List<?> privateGrants) {
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", 1L);
    tuple.put("accountAuthorityGeneration", 1L);
    tuple.put("tenantAuthorityGeneration", tenantGenerations);
    tuple.put("membershipAuthorityGeneration", membershipGenerations);
    tuple.put("privateRealmGrantVersions", privateGrants);
    return tuple;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return new LinkedHashMap<>((Map<String, Object>) value);
  }

  private static void assertInvalid(Map<String, Object> claims) {
    assertThatThrownBy(() -> ControlUiJwtProfileValidator.validateClaims(claims, 8))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasNoCause();
  }

  private static void assertInvalidAtCap(Map<String, Object> claims, int cap) {
    assertThatThrownBy(() -> ControlUiJwtProfileValidator.validateClaims(claims, cap))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasNoCause();
  }
}
