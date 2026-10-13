package net.firedevops.firemud.accountservice.service.session;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, read-only authentication of an initial Game Session Account delegation token.
 *
 * <p>This checks the TLS-authenticated Game Session workload, verifies only the unscoped initial
 * JWT shape, and asks the Account committed-issuance owner for current COMMITTED plus ACTIVE
 * evidence. It neither admits a player nor creates a lease, and its observation is not a reusable
 * credential or stand-alone acquire authorization. Callers still need the current source-specific
 * acquire predicates and durable lease-bound decision readback.
 */
public final class AccountGameplayAdmissionInitialTokenAuthenticator {
  private static final String ROUTE_ID = "game-session-initial-admission-acquire";
  private static final String GAME_SESSION_SERVICE = "game-session-service";
  private static final Set<String> NO_OPTIONAL_CLAIMS = Set.of();
  private static final ExplicitRouteProfilePolicy INITIAL_POLICY =
      new ExplicitRouteProfilePolicy(
          ROUTE_ID,
          GameSessionAccountDelegationProfile.PROFILE,
          GameSessionAccountDelegationProfile.TYPE,
          GameSessionAccountDelegationProfile.ISSUER,
          GameSessionAccountDelegationProfile.AUDIENCE,
          GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
          NO_OPTIONAL_CLAIMS,
          GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
          0,
          GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
          GameSessionAccountDelegationJwtProfileValidator::validateInitialClaims);

  private final AccountAsymmetricJwtVerifier verifier;
  private final AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner;
  private final String trustedWorkloadNamespace;
  private final Clock clock;

  public AccountGameplayAdmissionInitialTokenAuthenticator(
      AccountAsymmetricJwtVerifier verifier,
      AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner,
      String trustedWorkloadNamespace,
      Clock clock) {
    this.verifier = Objects.requireNonNull(verifier, "Account JWT verifier is required");
    this.committedIssuanceOwner =
        Objects.requireNonNull(committedIssuanceOwner, "Committed issuance owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(trustedWorkloadNamespace)) {
      throw new IllegalArgumentException("Trusted Game Session namespace is invalid");
    }
    this.trustedWorkloadNamespace = trustedWorkloadNamespace;
    this.clock = Objects.requireNonNull(clock, "Clock is required");
  }

  /**
   * Authenticates one compact initial token against the current committed and active owner state.
   *
   * <p>The compact token is neither retained nor included in any returned value or failure.
   */
  public CurrentInitialTokenObservation authenticateInitialToken(String compactJwt) {
    try {
      // Refuse caller-owned SQL work before the verifier can touch JWKS or the owner can touch SQL
      // and Redis. Both dependencies are deliberately called only outside ambient transactions.
      requireNoAmbientTransaction();
      GrpcPeerIdentity peer = requireCurrentGameSessionPeer();

      VerifiedClaims verified = verifier.verify(compactJwt, INITIAL_POLICY);
      requireVerifiedRoute(verified);
      Map<String, Object> claims = verified.claims();
      UUID tokenJti = canonicalUuid(claims.get("jti"));

      requireNoAmbientTransaction();
      CommittedCandidateVerificationData candidate =
          committedIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(tokenJti);
      long observedAt = clock.instant().getEpochSecond();
      requireCandidateAndClaims(peer, claims, verified, candidate, compactJwt, observedAt);
      return new CurrentInitialTokenObservation(candidate, observedAt);
    } catch (RuntimeException failure) {
      if (failure instanceof InitialTokenAuthenticationException denied) {
        throw denied;
      }
      // Do not expose token, verifier, SQL, signer, or registry details to the caller.
      throw denied();
    }
  }

  private GrpcPeerIdentity requireCurrentGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedUri =
        "spiffe://firemud/ns/" + trustedWorkloadNamespace + "/sa/" + GAME_SESSION_SERVICE;
    if (peer == null
        || !peer.isService(GAME_SESSION_SERVICE)
        || !peer.isInNamespace(trustedWorkloadNamespace)
        || !expectedUri.equals(peer.uri())) {
      throw denied();
    }
    return peer;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw denied();
    }
  }

  private static void requireVerifiedRoute(VerifiedClaims verified) {
    if (verified == null
        || !ROUTE_ID.equals(verified.routeId())
        || !GameSessionAccountDelegationProfile.PROFILE.equals(verified.profile())
        || !GameSessionAccountDelegationProfile.TYPE.equals(verified.tokenType())
        || !GameSessionAccountDelegationProfile.ISSUER.equals(verified.claims().get("iss"))
        || !GameSessionAccountDelegationProfile.AUDIENCE.equals(verified.claims().get("aud"))) {
      throw denied();
    }
  }

  private void requireCandidateAndClaims(
      GrpcPeerIdentity peer,
      Map<String, Object> claims,
      VerifiedClaims verified,
      CommittedCandidateVerificationData candidate,
      String compactJwt,
      long observedAtEpochSecond) {
    if (candidate == null) {
      throw denied();
    }
    AccountGameplayDelegationPendingIdentity identity = candidate.identity();
    AccountAuthoritySnapshot authority = candidate.authoritySnapshot();
    if (identity == null
        || authority == null
        || !identity.accountId().equals(authority.accountId())
        || !peer.uri().equals(identity.callerWorkload())
        || !candidate.signerKid().equals(verified.keyId())
        || !candidate.tokenSha256().equals(sha256(compactJwt))
        || !stringClaim(claims, "iss").equals(GameSessionAccountDelegationProfile.ISSUER)
        || !stringClaim(claims, "aud").equals(GameSessionAccountDelegationProfile.AUDIENCE)
        || !identity.accountId().toString().equals(stringClaim(claims, "sub"))
        || !identity.accountId().toString().equals(stringClaim(claims, "accountId"))
        || !identity.tokenJti().equals(canonicalUuid(claims.get("jti")))
        || epochSecondClaim(claims, "iat") != identity.issuedAtEpochSecond()
        || epochSecondClaim(claims, "nbf") != identity.notBeforeEpochSecond()
        || epochSecondClaim(claims, "exp") != identity.expiresAtEpochSecond()
        // Initial LOGIN issues durable token generation one; the committed owner verifies that
        // exact generation in its SQL/evidence proof before returning this candidate.
        || !"1".equals(stringClaim(claims, "tokenGeneration"))
        || !Long.toString(authority.issuanceFence()).equals(stringClaim(claims, "issuanceFence"))
        || !GameSessionAccountDelegationProfile.authorityTuple(
                authority.issuerGeneration(),
                authority.accountGeneration(),
                authority.accountSecurityCutoff())
            .equals(mapClaim(claims, "authorityTuple"))
        || !Map.of().equals(mapClaim(claims, "membershipVersion"))) {
      throw denied();
    }
    if (observedAtEpochSecond < identity.issuedAtEpochSecond()
        || observedAtEpochSecond < identity.notBeforeEpochSecond()
        || observedAtEpochSecond >= identity.expiresAtEpochSecond()) {
      throw denied();
    }
  }

  private static UUID canonicalUuid(Object value) {
    if (!(value instanceof String text)) {
      throw denied();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.version() != 4 || parsed.variant() != 2) {
        throw denied();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw denied();
    }
  }

  private static String stringClaim(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    if (!(value instanceof String text)) {
      throw denied();
    }
    return text;
  }

  private static long epochSecondClaim(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    try {
      if (!(value instanceof Number number)) {
        throw denied();
      }
      return new BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException failure) {
      throw denied();
    }
  }

  private static Map<?, ?> mapClaim(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    if (!(value instanceof Map<?, ?> map)) {
      throw denied();
    }
    return map;
  }

  private static String sha256(String compactJwt) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(compactJwt.getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static InitialTokenAuthenticationException denied() {
    return new InitialTokenAuthenticationException();
  }

  /** Generic fail-closed error; deliberately contains no token or dependency details. */
  public static final class InitialTokenAuthenticationException extends SecurityException {
    private InitialTokenAuthenticationException() {
      super("Initial Account token authentication failed");
    }
  }

  /**
   * Read-only currentness observation. It cannot authorize admission or replace source predicates
   * and durable decision readback at a later acquire boundary.
   */
  public static final class CurrentInitialTokenObservation {
    private final CommittedCandidateVerificationData candidate;
    private final long observedAtEpochSecond;

    private CurrentInitialTokenObservation(
        CommittedCandidateVerificationData candidate, long observedAtEpochSecond) {
      this.candidate = Objects.requireNonNull(candidate);
      this.observedAtEpochSecond = observedAtEpochSecond;
    }

    public CommittedCandidateVerificationData candidate() {
      return candidate;
    }

    public long observedAtEpochSecond() {
      return observedAtEpochSecond;
    }

    @Override
    public String toString() {
      return "CurrentInitialTokenObservation[redacted]";
    }
  }
}
