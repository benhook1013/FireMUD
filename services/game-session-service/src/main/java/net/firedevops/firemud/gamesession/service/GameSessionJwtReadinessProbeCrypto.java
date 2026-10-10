package net.firedevops.firemud.gamesession.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverProtoMapper.ParsedRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;

/** Production-profile cryptographic verification with a non-authorizing readiness result only. */
public final class GameSessionJwtReadinessProbeCrypto {
  private static final String ISSUER = "firemud-account-service";
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final String CANARY_TYPE = "account_jwt_readiness_canary";
  private static final Set<String> CANARY_CLAIMS =
      Set.of("iss", "sub", "jti", "aud", "iat", "nbf", "exp", "tokenProfile", "tokenType");
  private static final Set<String> INITIAL_AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");

  private final AccountAsymmetricJwtVerifier verifier;
  private final int maxControlUiTenantScopes;

  public GameSessionJwtReadinessProbeCrypto(
      AccountAsymmetricJwtVerifier verifier, int maxControlUiTenantScopes) {
    this.verifier = java.util.Objects.requireNonNull(verifier);
    if (maxControlUiTenantScopes <= 0
        || maxControlUiTenantScopes
            > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw new IllegalArgumentException("Control UI profile scope bound is invalid");
    }
    this.maxControlUiTenantScopes = maxControlUiTenantScopes;
  }

  /**
   * Verifies signature, production profile shape, exact owner coordinates, and non-authorizing use.
   */
  public VerifiedProbe verify(ParsedRequest request) {
    ExplicitRouteProfilePolicy policy = policy(request);
    VerifiedClaims claims = verifier.verify(request.compactJwt(), policy);
    validateOwnerBoundProbe(request, claims);
    ReceiveReadinessProbeResponse.ObservationOutcome outcome =
        request.probeKind() == GameSessionJwtReadinessReceiverProtoMapper.ProbeKind.CANARY
                || GameSessionAccountDelegationProfile.PROFILE.equals(
                    request.coordinates().getTokenProfile())
            ? ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED
            : ReceiveReadinessProbeResponse.ObservationOutcome.INAPPLICABLE_REJECT;
    var expectedOutcome =
        outcome == ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED
            ? net.firedevops.firemud.account.v1.ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
            : net.firedevops.firemud.account.v1.ReadinessProbeCoordinates.ExpectedOutcome
                .INAPPLICABLE_REJECT;
    if (request.expectedOutcome() != expectedOutcome) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    return new VerifiedProbe(claims.keyId(), outcome);
  }

  private ExplicitRouteProfilePolicy policy(ParsedRequest request) {
    if (request.probeKind() == GameSessionJwtReadinessReceiverProtoMapper.ProbeKind.CANARY) {
      return new ExplicitRouteProfilePolicy(
          "game-session-jwt-readiness-canary",
          GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
          CANARY_TYPE,
          ISSUER,
          GameSessionJwtReadinessReceiverProtoMapper.CANARY_AUDIENCE,
          CANARY_CLAIMS,
          Set.of(),
          GameSessionJwtReadinessReceiverProtoMapper.MAX_PROBE_LIFETIME_SECONDS,
          5L,
          GameSessionJwtReadinessReceiverProtoMapper.MAX_COMPACT_JWT_BYTES,
          GameSessionJwtReadinessProbeCrypto::validateCanaryShape);
    }
    String profile = request.coordinates().getTokenProfile();
    return switch (profile) {
      case ControlUiJwtProfileValidator.PROFILE ->
          new ExplicitRouteProfilePolicy(
              "game-session-jwt-readiness-control-ui",
              ControlUiJwtProfileValidator.PROFILE,
              ControlUiJwtProfileValidator.TOKEN_TYPE,
              ISSUER,
              ControlUiJwtProfileValidator.AUDIENCE,
              ControlUiJwtProfileValidator.REQUIRED_CLAIMS,
              ControlUiJwtProfileValidator.OPTIONAL_CLAIMS,
              GameSessionJwtReadinessReceiverProtoMapper.MAX_PROBE_LIFETIME_SECONDS,
              5L,
              GameSessionJwtReadinessReceiverProtoMapper.MAX_COMPACT_JWT_BYTES,
              claims ->
                  ControlUiJwtProfileValidator.validateClaims(claims, maxControlUiTenantScopes));
      case PlayerBootstrapJwtProfileValidator.PROFILE ->
          new ExplicitRouteProfilePolicy(
              "game-session-jwt-readiness-player-bootstrap",
              PlayerBootstrapJwtProfileValidator.PROFILE,
              PlayerBootstrapJwtProfileValidator.TOKEN_TYPE,
              ISSUER,
              PlayerBootstrapJwtProfileValidator.AUDIENCE,
              PlayerBootstrapJwtProfileValidator.REQUIRED_CLAIMS,
              PlayerBootstrapJwtProfileValidator.OPTIONAL_CLAIMS,
              GameSessionJwtReadinessReceiverProtoMapper.MAX_PROBE_LIFETIME_SECONDS,
              5L,
              GameSessionJwtReadinessReceiverProtoMapper.MAX_COMPACT_JWT_BYTES,
              PlayerBootstrapJwtProfileValidator::validateClaims);
      case GameSessionAccountDelegationProfile.PROFILE ->
          new ExplicitRouteProfilePolicy(
              "game-session-jwt-readiness-game-session-account-delegation",
              GameSessionAccountDelegationProfile.PROFILE,
              GameSessionAccountDelegationProfile.TYPE,
              GameSessionAccountDelegationProfile.ISSUER,
              GameSessionAccountDelegationProfile.AUDIENCE,
              GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
              GameSessionAccountDelegationJwtProfileValidator.OPTIONAL_CLAIMS,
              GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
              5L,
              GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
              GameSessionAccountDelegationJwtProfileValidator::validateClaims);
      default -> throw new AccountAsymmetricJwtVerifier.VerificationException();
    };
  }

  private static void validateCanaryShape(Map<String, Object> claims) {
    if (!CANARY_CLAIMS.equals(claims.keySet())
        || !ISSUER.equals(claims.get("iss"))
        || !GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE.equals(
            claims.get("tokenProfile"))
        || !CANARY_TYPE.equals(claims.get("tokenType"))) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static void validateOwnerBoundProbe(ParsedRequest request, VerifiedClaims verified) {
    Map<String, Object> claims = verified.claims();
    var coordinates = request.coordinates();
    boolean canary =
        request.probeKind() == GameSessionJwtReadinessReceiverProtoMapper.ProbeKind.CANARY;
    if (!verified.profile().equals(coordinates.getTokenProfile())
        || !verified.keyId().equals(coordinates.getTargetKid())
        || !ISSUER.equals(claims.get("iss"))
        || !coordinates.getAudience().equals(claims.get("aud"))
        || !RESERVED_SUBJECT.equals(claims.get("sub"))
        || !request.jti().toString().equals(claims.get("jti"))
        || exactLong(claims.get("iat")) != coordinates.getIssuedAtEpochSeconds()
        || exactLong(claims.get("nbf")) != coordinates.getIssuedAtEpochSeconds()
        || exactLong(claims.get("exp")) != coordinates.getExpiresAtEpochSeconds()) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    if (canary) {
      if (!CANARY_CLAIMS.equals(claims.keySet())) {
        throw new AccountAsymmetricJwtVerifier.VerificationException();
      }
      return;
    }
    if (!RESERVED_SUBJECT.equals(claims.get("accountId"))
        || !exactOneCounter(claims.get("tokenGeneration"), verified.profile())
        || !exactOneCounter(claims.get("issuanceFence"), verified.profile())
        || !exactAuthorityTuple(claims.get("authorityTuple"), verified.profile())
        || !(claims.get("membershipVersion") instanceof Map<?, ?> membershipVersion)
        || !membershipVersion.isEmpty()
        || !noRoles(claims)
        || !noScopes(claims, verified.profile())
        || (GameSessionAccountDelegationProfile.PROFILE.equals(verified.profile())
            && claims.containsKey("tenantId"))) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static boolean exactOneCounter(Object value, String profile) {
    return GameSessionAccountDelegationProfile.PROFILE.equals(profile)
        ? "1".equals(value)
        : exactLong(value) == 1L;
  }

  private static boolean exactAuthorityTuple(Object value, String profile) {
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
        && tuple.get("privateRealmGrantVersions") instanceof List<?> grants
        && grants.isEmpty();
  }

  private static boolean noRoles(Map<String, Object> claims) {
    if (!claims.containsKey("globalRoles")) {
      return true;
    }
    return claims.get("globalRoles") instanceof List<?> roles && roles.isEmpty();
  }

  private static boolean noScopes(Map<String, Object> claims, String profile) {
    if (GameSessionAccountDelegationProfile.PROFILE.equals(profile)
        && !claims.containsKey("scopedRoles")) {
      return true;
    }
    return claims.get("scopedRoles") instanceof Map<?, ?> scopes && scopes.isEmpty();
  }

  private static long exactLong(Object value) {
    if (!(value instanceof Number number)) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    try {
      return new BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  public record VerifiedProbe(
      String keyId, ReceiveReadinessProbeResponse.ObservationOutcome outcome) {}
}
