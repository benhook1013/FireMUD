package net.firedevops.firemud.accountservice.service.session;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;

/** Shared pure policy and claim checks for Account's readiness probes. */
public final class AccountJwtReadinessProbeCrypto {
  static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  static final String ISSUER = "firemud-account-service";
  static final String CANARY_PROFILE = "account-jwt-readiness-canary";
  static final String CANARY_TYPE = "account_jwt_readiness_canary";
  static final String CANARY_AUDIENCE = "firemud-account-jwt-readiness";
  static final String VALIDATOR_ID = "account-service";

  private static final int MAX_COMPACT_TOKEN_BYTES = 16 * 1024;
  private static final long MAX_TOKEN_LIFETIME_SECONDS = 300L;
  private static final long CLOCK_SKEW_SECONDS = 5L;
  private static final Set<String> CANARY_CLAIMS =
      Set.of("iss", "sub", "jti", "aud", "iat", "nbf", "exp", "tokenProfile", "tokenType");
  private static final Set<String> INITIAL_AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Map<String, ProfileDescriptor> REPRESENTATIVE_PROFILES =
      Map.of(
          ControlUiJwtProfileValidator.PROFILE,
          new ProfileDescriptor(
              ControlUiJwtProfileValidator.PROFILE,
              ControlUiJwtProfileValidator.AUDIENCE,
              ControlUiJwtProfileValidator.TOKEN_TYPE,
              ControlUiJwtProfileValidator.REQUIRED_CLAIMS,
              ControlUiJwtProfileValidator.OPTIONAL_CLAIMS),
          PlayerBootstrapJwtProfileValidator.PROFILE,
          new ProfileDescriptor(
              PlayerBootstrapJwtProfileValidator.PROFILE,
              PlayerBootstrapJwtProfileValidator.AUDIENCE,
              PlayerBootstrapJwtProfileValidator.TOKEN_TYPE,
              PlayerBootstrapJwtProfileValidator.REQUIRED_CLAIMS,
              PlayerBootstrapJwtProfileValidator.OPTIONAL_CLAIMS),
          GameSessionAccountDelegationProfile.PROFILE,
          new ProfileDescriptor(
              GameSessionAccountDelegationProfile.PROFILE,
              GameSessionAccountDelegationProfile.AUDIENCE,
              GameSessionAccountDelegationProfile.TYPE,
              GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
              GameSessionAccountDelegationJwtProfileValidator.OPTIONAL_CLAIMS));

  private AccountJwtReadinessProbeCrypto() {}

  static Map<String, ProfileDescriptor> representativeProfiles() {
    return REPRESENTATIVE_PROFILES;
  }

  static ProfileDescriptor representativeProfile(String profile) {
    return REPRESENTATIVE_PROFILES.get(profile);
  }

  static ExplicitRouteProfilePolicy policy(
      ProbeKind kind, String profile, int maxControlUiTenantScopes) {
    if (kind == ProbeKind.CANARY) {
      return new ExplicitRouteProfilePolicy(
          "account-jwt-readiness-canary",
          CANARY_PROFILE,
          CANARY_TYPE,
          ISSUER,
          CANARY_AUDIENCE,
          CANARY_CLAIMS,
          Set.of(),
          MAX_TOKEN_LIFETIME_SECONDS,
          CLOCK_SKEW_SECONDS,
          MAX_COMPACT_TOKEN_BYTES,
          AccountJwtReadinessProbeCrypto::validateCanaryShape);
    }
    ProfileDescriptor descriptor = REPRESENTATIVE_PROFILES.get(profile);
    if (descriptor == null) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    return new ExplicitRouteProfilePolicy(
        "account-jwt-readiness-" + descriptor.profile(),
        descriptor.profile(),
        descriptor.tokenType(),
        ISSUER,
        descriptor.audience(),
        descriptor.requiredClaims(),
        descriptor.optionalClaims(),
        MAX_TOKEN_LIFETIME_SECONDS,
        CLOCK_SKEW_SECONDS,
        MAX_COMPACT_TOKEN_BYTES,
        claims -> validateProfileShape(descriptor.profile(), claims, maxControlUiTenantScopes));
  }

  static VerifiedClaims verify(
      AccountAsymmetricJwtVerifier verifier,
      String compactJwt,
      ExpectedProbe expected,
      int maxControlUiTenantScopes) {
    Objects.requireNonNull(verifier);
    Objects.requireNonNull(expected);
    VerifiedClaims verified =
        verifier.verify(
            compactJwt,
            policy(expected.probeKind(), expected.tokenProfile(), maxControlUiTenantScopes));
    validateSignedClaims(verified, expected);
    return verified;
  }

  static ExpectedProbe expectedProbe(
      ProbeKind kind,
      String tokenProfile,
      String audience,
      String jti,
      String targetKid,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond) {
    if (kind == ProbeKind.CANARY) {
      if (!CANARY_PROFILE.equals(tokenProfile) || !CANARY_AUDIENCE.equals(audience)) {
        throw new AccountAsymmetricJwtVerifier.VerificationException();
      }
    } else {
      ProfileDescriptor descriptor = REPRESENTATIVE_PROFILES.get(tokenProfile);
      if (descriptor == null || !descriptor.audience().equals(audience)) {
        throw new AccountAsymmetricJwtVerifier.VerificationException();
      }
    }
    return new ExpectedProbe(
        kind, tokenProfile, audience, jti, targetKid, issuedAtEpochSecond, expiresAtEpochSecond);
  }

  private static void validateSignedClaims(VerifiedClaims verified, ExpectedProbe expected) {
    Map<String, Object> claims = verified.claims();
    boolean canary = expected.probeKind() == ProbeKind.CANARY;
    ProfileDescriptor profile =
        canary ? null : REPRESENTATIVE_PROFILES.get(expected.tokenProfile());
    if (profile == null && !canary) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    String expectedPolicyType = canary ? CANARY_TYPE : profile.tokenType();
    if (!verified.profile().equals(expected.tokenProfile())
        || !verified.tokenType().equals(expectedPolicyType)
        || !verified.keyId().equals(expected.targetKid())
        || !ISSUER.equals(claims.get("iss"))
        || !expected.audience().equals(claims.get("aud"))
        || !RESERVED_SUBJECT.equals(claims.get("sub"))
        || !expected.jti().equals(claims.get("jti"))
        || exactLong(claims.get("iat")) != expected.issuedAtEpochSecond()
        || exactLong(claims.get("nbf")) != expected.issuedAtEpochSecond()
        || exactLong(claims.get("exp")) != expected.expiresAtEpochSecond()) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    if (canary) {
      if (!CANARY_CLAIMS.equals(claims.keySet())
          || !CANARY_PROFILE.equals(claims.get("tokenProfile"))
          || !CANARY_TYPE.equals(claims.get("tokenType"))) {
        throw new AccountAsymmetricJwtVerifier.VerificationException();
      }
      return;
    }
    if (!RESERVED_SUBJECT.equals(claims.get("accountId"))
        || !exactOneCounter(claims.get("tokenGeneration"), profile)
        || !exactOneCounter(claims.get("issuanceFence"), profile)
        || !exactAuthorityTuple(claims.get("authorityTuple"), profile)
        || !(claims.get("membershipVersion") instanceof Map<?, ?> membershipVersion)
        || !membershipVersion.isEmpty()
        || !noRoleClaims(claims)
        || !noScopeClaims(profile, claims)) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static void validateCanaryShape(Map<String, Object> claims) {
    if (!CANARY_CLAIMS.equals(claims.keySet())
        || !ISSUER.equals(claims.get("iss"))
        || !CANARY_PROFILE.equals(claims.get("tokenProfile"))
        || !CANARY_TYPE.equals(claims.get("tokenType"))) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static void validateProfileShape(
      String profile, Map<String, Object> claims, int maxControlUiTenantScopes) {
    switch (profile) {
      case ControlUiJwtProfileValidator.PROFILE ->
          ControlUiJwtProfileValidator.validateClaims(claims, maxControlUiTenantScopes);
      case PlayerBootstrapJwtProfileValidator.PROFILE ->
          PlayerBootstrapJwtProfileValidator.validateClaims(claims);
      case GameSessionAccountDelegationProfile.PROFILE ->
          GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims);
      default -> throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static boolean noRoleClaims(Map<String, Object> claims) {
    if (claims.containsKey("globalRoles")) {
      return claims.get("globalRoles") instanceof java.util.List<?> roles && roles.isEmpty();
    }
    return true;
  }

  private static boolean noScopeClaims(ProfileDescriptor profile, Map<String, Object> claims) {
    if (profile.profile().equals(GameSessionAccountDelegationProfile.PROFILE)
        && !claims.containsKey("scopedRoles")) {
      return true;
    }
    return claims.get("scopedRoles") instanceof Map<?, ?> scopedRoles && scopedRoles.isEmpty();
  }

  private static boolean exactOneCounter(Object value, ProfileDescriptor profile) {
    return GameSessionAccountDelegationProfile.PROFILE.equals(profile.profile())
        ? "1".equals(value)
        : exactLong(value) == 1L;
  }

  private static boolean exactAuthorityTuple(Object value, ProfileDescriptor profile) {
    if (!(value instanceof Map<?, ?> tuple)
        || !tuple.keySet().equals(INITIAL_AUTHORITY_TUPLE_FIELDS)) {
      return false;
    }
    return exactOneCounter(tuple.get("issuerAuthGeneration"), profile)
        && exactOneCounter(tuple.get("accountAuthorityGeneration"), profile)
        && tuple.get("tenantAuthorityGeneration") instanceof Map<?, ?> tenantAuthority
        && tenantAuthority.isEmpty()
        && tuple.get("membershipAuthorityGeneration") instanceof Map<?, ?> membershipAuthority
        && membershipAuthority.isEmpty()
        && tuple.get("privateRealmGrantVersions") instanceof java.util.List<?> grants
        && grants.isEmpty();
  }

  private static long exactLong(Object value) {
    if (!(value instanceof Number number)) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    try {
      return new BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException invalidNumber) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  public static AccountAsymmetricJwtVerifier createVerifier(
      AccountJwtJwksTrustedSource trustedSource, Clock clock) {
    Objects.requireNonNull(trustedSource, "Protected Account JWKS source is required");
    var sourceIdentity = trustedSource.sourceIdentity();
    var cache =
        new AccountPublicJwksCache(trustedSource, sourceIdentity, clock, Duration.ofSeconds(300));
    return new AccountAsymmetricJwtVerifier(cache, clock);
  }

  record ProfileDescriptor(
      String profile,
      String audience,
      String tokenType,
      Set<String> requiredClaims,
      Set<String> optionalClaims) {}

  record ExpectedProbe(
      ProbeKind probeKind,
      String tokenProfile,
      String audience,
      String jti,
      String targetKid,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond) {}
}
