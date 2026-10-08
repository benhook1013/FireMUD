package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameSessionAccountDelegationJwtProfileValidatorTest {
  @Test
  void rejectsNullClaimsBeforeSelectingEitherDelegationForm() {
    assertThatThrownBy(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(null))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasNoCause();
  }

  @Test
  void independentlyScopedAccountAndTenantUuidsMayEqualWithoutRelaxingExactMapKeys() {
    var claims = validClaims();
    var tuple = object(claims.get("authorityTuple"));
    tuple.put("tenantAuthorityGeneration", Map.of(ACCOUNT, "5"));
    tuple.put("membershipAuthorityGeneration", Map.of(ACCOUNT, "7"));
    claims.put("tenantId", ACCOUNT);
    claims.put("authorityTuple", tuple);
    claims.put("membershipVersion", Map.of(ACCOUNT, "3"));
    assertThatCode(
            () ->
                GameSessionAccountDelegationJwtProfileValidator.validatePublicTenantBoundClaims(
                    claims))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> GameSessionAccountDelegationJwtProfileValidator.validateInitialClaims(claims))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    claims.put("membershipVersion", Map.of("22222222-2222-4222-8222-222222222222", "3"));
    assertInvalid(claims);
  }

  @Test
  void publicTenantBoundFormKeepsIndependentOneTenantVersionsAndCannotAuthenticateInitialLogin() {
    String tenant = "22222222-2222-4222-8222-222222222222";
    Map<String, Object> claims = validClaims();
    var tuple = object(claims.get("authorityTuple"));
    tuple.put("tenantAuthorityGeneration", Map.of(tenant, "5"));
    tuple.put("membershipAuthorityGeneration", Map.of(tenant, "7"));
    claims.put("tenantId", tenant);
    claims.put("authorityTuple", tuple);
    claims.put("membershipVersion", Map.of(tenant, "3"));
    assertThatCode(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> GameSessionAccountDelegationJwtProfileValidator.validateInitialClaims(claims))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    claims.put("membershipVersion", Map.of());
    assertInvalid(claims);
    claims.put("membershipVersion", Map.of(ACCOUNT, "3"));
    assertInvalid(claims);
    claims.put("membershipVersion", Map.of(tenant, "3", ACCOUNT, "4"));
    assertInvalid(claims);
  }

  @Test
  void tenantBillingCutoffMustUseTheExactTenantStreamAndAccountCutoffMustUseTheTokenAccount() {
    String tenant = "22222222-2222-4222-8222-222222222222";
    Map<String, Object> claims = validClaims();
    Map<String, Object> tuple = object(claims.get("authorityTuple"));
    tuple.put("accountAuthorityGeneration", "12");
    tuple.put("tenantAuthorityGeneration", Map.of(tenant, "5"));
    tuple.put("membershipAuthorityGeneration", Map.of(tenant, "7"));
    tuple.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT,
            "outboxSequence",
            "44"));
    tuple.put(
        "tenantBillingCutoff",
        Map.of(
            tenant,
            Map.of(
                "tenantAuthorityGeneration",
                "5",
                "tenantBillingSequence",
                "8",
                "outboxStreamKey",
                "account:auth-authority:v1:tenant/" + tenant,
                "outboxSequence",
                "45")));
    claims.put("tenantId", tenant);
    claims.put("authorityTuple", tuple);
    claims.put("membershipVersion", Map.of(tenant, "3"));

    assertThatCode(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();

    Map<String, Object> crossAccount = object(tuple);
    crossAccount.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:account/33333333-3333-4333-8333-333333333333",
            "outboxSequence",
            "44"));
    claims.put("authorityTuple", crossAccount);
    assertInvalid(claims);

    Map<String, Object> crossTenantStream = object(tuple);
    crossTenantStream.put(
        "tenantBillingCutoff",
        Map.of(
            tenant,
            Map.of(
                "tenantAuthorityGeneration",
                "5",
                "tenantBillingSequence",
                "8",
                "outboxStreamKey",
                "account:auth-authority:v1:tenant/33333333-3333-4333-8333-333333333333",
                "outboxSequence",
                "45")));
    claims.put("authorityTuple", crossTenantStream);
    assertInvalid(claims);

    Map<String, Object> crossTenantKey = object(tuple);
    crossTenantKey.put(
        "tenantBillingCutoff",
        Map.of(
            "33333333-3333-4333-8333-333333333333",
            Map.of(
                "tenantAuthorityGeneration",
                "5",
                "tenantBillingSequence",
                "8",
                "outboxStreamKey",
                "account:auth-authority:v1:tenant/" + tenant,
                "outboxSequence",
                "45")));
    claims.put("authorityTuple", crossTenantKey);
    assertInvalid(claims);
  }

  @Test
  void tenantDiscriminatorNeverAdoptsInitialEmptyTuple() {
    var claims = validClaims();
    claims.put("tenantId", "22222222-2222-4222-8222-222222222222");
    assertInvalid(claims);
  }

  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";

  @Test
  void acceptsExactUnscopedDelegationAndOptionalEmptyRoleCollections() {
    Map<String, Object> claims = validClaims();

    assertThatCode(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();
    claims.put("globalRoles", List.of());
    claims.put("scopedRoles", Map.of());
    assertThatCode(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsIndependentPositiveAccountGenerationAndCutoffSequenceWithExactAccountScope() {
    Map<String, Object> claims = validClaims();
    Map<String, Object> tuple = object(claims.get("authorityTuple"));
    tuple.put("accountAuthorityGeneration", "12");
    tuple.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT,
            "outboxSequence",
            "44"));
    claims.put("authorityTuple", tuple);

    assertThatCode(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims))
        .doesNotThrowAnyException();

    Map<String, Object> wrongGeneration = object(claims.get("authorityTuple"));
    wrongGeneration.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "11",
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT,
            "outboxSequence",
            "44"));
    claims.put("authorityTuple", wrongGeneration);
    assertInvalid(claims);

    Map<String, Object> malformedSequence = object(tuple);
    malformedSequence.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT,
            "outboxSequence",
            "044"));
    claims.put("authorityTuple", malformedSequence);
    assertInvalid(claims);

    Map<String, Object> zeroSequence = object(tuple);
    zeroSequence.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT,
            "outboxSequence",
            "0"));
    claims.put("authorityTuple", zeroSequence);
    assertInvalid(claims);

    Map<String, Object> wrongScope = object(tuple);
    wrongScope.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:tenant/22222222-2222-4222-8222-222222222222",
            "outboxSequence",
            "44"));
    claims.put("authorityTuple", wrongScope);
    assertInvalid(claims);

    Map<String, Object> wrongAccountScope = object(tuple);
    wrongAccountScope.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            "12",
            "outboxStreamKey",
            "account:auth-authority:v1:account/33333333-3333-4333-8333-333333333333",
            "outboxSequence",
            "44"));
    claims.put("authorityTuple", wrongAccountScope);
    assertInvalid(claims);
  }

  @Test
  void rejectsTenantAuthorityScopeAndUnexpectedClaims() {
    Map<String, Object> scoped = validClaims();
    Map<String, Object> tuple = object(scoped.get("authorityTuple"));
    tuple.put("tenantAuthorityGeneration", Map.of("22222222-2222-4222-8222-222222222222", 2L));
    scoped.put("authorityTuple", tuple);
    assertInvalid(scoped);

    Map<String, Object> scopedRoles = validClaims();
    scopedRoles.put(
        "scopedRoles", Map.of("22222222-2222-4222-8222-222222222222", List.of("reader")));
    assertInvalid(scopedRoles);

    Map<String, Object> billing = validClaims();
    tuple = object(billing.get("authorityTuple"));
    tuple.put("tenantBillingCutoff", Map.of("22222222-2222-4222-8222-222222222222", Map.of()));
    billing.put("authorityTuple", tuple);
    assertInvalid(billing);
  }

  @Test
  void rejectsNumericOrNoncanonicalAuthorityCountersWhileKeepingEpochTimesNumeric() {
    Map<String, Object> numericTokenGeneration = validClaims();
    numericTokenGeneration.put("tokenGeneration", 1L);
    assertInvalid(numericTokenGeneration);

    Map<String, Object> numericFence = validClaims();
    numericFence.put("issuanceFence", 1L);
    assertInvalid(numericFence);

    Map<String, Object> numericTuple = validClaims();
    Map<String, Object> tuple = object(numericTuple.get("authorityTuple"));
    tuple.put("issuerAuthGeneration", 1L);
    numericTuple.put("authorityTuple", tuple);
    assertInvalid(numericTuple);

    Map<String, Object> leadingZero = validClaims();
    leadingZero.put("issuanceFence", "01");
    assertInvalid(leadingZero);

    Map<String, Object> fractionalTime = validClaims();
    fractionalTime.put("iat", 1.5d);
    assertInvalid(fractionalTime);
  }

  private static Map<String, Object> validClaims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", GameSessionAccountDelegationProfile.ISSUER);
    claims.put("sub", ACCOUNT);
    claims.put("accountId", ACCOUNT);
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
    claims.put("iat", 1L);
    claims.put("nbf", 1L);
    claims.put("exp", 300L);
    claims.put("tokenGeneration", "1");
    claims.put("issuanceFence", "1");
    claims.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(),
            "membershipAuthorityGeneration", Map.of(),
            "privateRealmGrantVersions", List.of()));
    claims.put("membershipVersion", Map.of());
    return claims;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return new LinkedHashMap<>((Map<String, Object>) value);
  }

  private static void assertInvalid(Map<String, Object> claims) {
    assertThatThrownBy(() -> GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasNoCause();
  }
}
