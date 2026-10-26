package net.firedevops.firemud.accountservice.service.session;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account source component for internal gameplay-admission owner integration.
 *
 * <p>This source has no runtime registration or callable API. Successful verification is only a
 * private input to an admission composition; it is not membership, entitlement, current admission,
 * or a Game Session binding decision. The presented token is never retained or returned.
 */
public final class AccountGameplayAdmissionCallerVerifier {
  private static final Duration MAX_JWKS_CACHE_AGE = Duration.ofSeconds(30);
  private static final ExplicitRouteProfilePolicy POLICY =
      new ExplicitRouteProfilePolicy(
          "account-gameplay-admission-caller",
          GameSessionAccountDelegationProfile.PROFILE,
          GameSessionAccountDelegationProfile.TYPE,
          GameSessionAccountDelegationProfile.ISSUER,
          GameSessionAccountDelegationProfile.AUDIENCE,
          GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
          GameSessionAccountDelegationJwtProfileValidator.OPTIONAL_CLAIMS,
          GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
          5,
          GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
          GameSessionAccountDelegationJwtProfileValidator::validateClaims);

  private final AccountJwtJwksTrustedSource trustedJwksSource;
  private final AccountGameplayDelegationCommittedIssuanceOwner currentIssuanceOwner;
  private final Clock clock;
  private final Object cacheMonitor = new Object();
  private AccountPublicJwksCache publicJwksCache;

  public AccountGameplayAdmissionCallerVerifier(
      AccountJwtJwksTrustedSource trustedJwksSource,
      AccountGameplayDelegationCommittedIssuanceOwner currentIssuanceOwner,
      Clock clock) {
    this.trustedJwksSource = Objects.requireNonNull(trustedJwksSource);
    this.currentIssuanceOwner = Objects.requireNonNull(currentIssuanceOwner);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Verifies signature and the fixed initial LOGIN profile before using the token JTI as a lookup
   * hint for Account's current committed issuance and exact ACTIVE registry owner.
   */
  public VerifiedCaller verifyPresentedCompactToken(String compactJwt) {
    if (compactJwt == null
        || compactJwt.isBlank()
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }

    try {
      SourceIdentity trustedPin = trustedJwksSource.sourceIdentity();
      VerifiedClaims verified =
          new AccountAsymmetricJwtVerifier(currentCache(trustedPin), clock)
              .verify(compactJwt, POLICY);

      // The cryptographically and profile-verified JTI is only a lookup selector. It is not
      // passed to Account's committed-currentness owner until signature/profile validation above.
      UUID tokenJti = requireCanonicalUuid(verified.claims().get("jti"));
      CommittedCandidateVerificationData candidate =
          currentIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(tokenJti);
      requireExactCandidate(compactJwt, verified, candidate);
      return new VerifiedCaller(candidate);
    } catch (RuntimeException failure) {
      // Do not preserve exception text or causes that could expose credential or parser details.
      throw unavailable();
    }
  }

  private AccountPublicJwksCache currentCache(SourceIdentity identity) {
    synchronized (cacheMonitor) {
      if (publicJwksCache == null || !identity.equals(publicJwksCache.sourceIdentity())) {
        publicJwksCache =
            new AccountPublicJwksCache(trustedJwksSource, identity, clock, MAX_JWKS_CACHE_AGE);
      }
      return publicJwksCache;
    }
  }

  private static void requireExactCandidate(
      String compactJwt, VerifiedClaims verified, CommittedCandidateVerificationData candidate) {
    Objects.requireNonNull(candidate);
    AccountGameplayDelegationPendingIdentity identity = candidate.identity();
    Map<String, Object> claims = verified.claims();
    var authority = candidate.authoritySnapshot();
    Map<String, Object> expectedAuthorityTuple =
        GameSessionAccountDelegationProfile.authorityTuple(
            authority.issuerGeneration(),
            authority.accountGeneration(),
            authority.accountSecurityCutoff());

    byte[] compactBytes = compactJwt.getBytes(StandardCharsets.UTF_8);
    String compactDigest;
    try {
      compactDigest =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compactBytes));
    } catch (NoSuchAlgorithmException failure) {
      throw unavailable();
    } finally {
      Arrays.fill(compactBytes, (byte) 0);
    }

    Object membershipVersion = claims.get("membershipVersion");
    if (!GameSessionAccountDelegationProfile.PROFILE.equals(verified.profile())
        || !GameSessionAccountDelegationProfile.TYPE.equals(verified.tokenType())
        || !candidate.signerKid().equals(verified.keyId())
        || !identity.accountId().toString().equals(claims.get("sub"))
        || !identity.accountId().toString().equals(claims.get("accountId"))
        || !identity.tokenJti().toString().equals(claims.get("jti"))
        || !identity.tokenJti().equals(requireCanonicalUuid(claims.get("jti")))
        // The current Account issuance reader selects only its supported generation-1 profile.
        || positiveCounter(claims, "tokenGeneration") != 1L
        || numberClaim(claims, "iat") != identity.issuedAtEpochSecond()
        || numberClaim(claims, "nbf") != identity.notBeforeEpochSecond()
        || numberClaim(claims, "exp") != identity.expiresAtEpochSecond()
        || positiveCounter(claims, "issuanceFence") != authority.issuanceFence()
        || !(membershipVersion instanceof Map<?, ?> versionMap)
        || !versionMap.isEmpty()
        || !expectedAuthorityTuple.equals(claims.get("authorityTuple"))
        || claims.containsKey("globalRoles")
        || claims.containsKey("scopedRoles")
        || !identity.accountId().equals(authority.accountId())
        || !compactDigest.equals(candidate.tokenSha256())) {
      throw unavailable();
    }
  }

  private static UUID requireCanonicalUuid(Object value) {
    if (!(value instanceof String text)) throw unavailable();
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.version() != 4 || parsed.variant() != 2) {
        throw unavailable();
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
  }

  private static long positiveCounter(Map<String, Object> claims, String field) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(claims.get(field)).longValueExact();
    } catch (IllegalArgumentException | ArithmeticException malformed) {
      throw unavailable();
    }
  }

  private static long numberClaim(Map<String, Object> claims, String field) {
    Object value = claims.get(field);
    if (!(value instanceof Number number)) throw unavailable();
    try {
      return new BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException malformed) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account gameplay caller verification is unavailable");
  }

  /**
   * Unforgeable Account-source result for the manually composed admission owner. Its public
   * accessors expose only immutable owner identity/current-candidate evidence; it retains no JWT,
   * claim map, or registry bytes. The committed currentness owner returns only after the immutable
   * token fence is ACTIVE; AccountGameplayTokenIdentityFence constrains that fence value to 1.
   */
  public static final class VerifiedCaller {
    private final CommittedCandidateVerificationData committedCandidate;

    private VerifiedCaller(CommittedCandidateVerificationData committedCandidate) {
      this.committedCandidate = Objects.requireNonNull(committedCandidate);
    }

    public CommittedCandidateVerificationData currentCandidate() {
      return committedCandidate;
    }

    CommittedCandidateVerificationData committedCandidate() {
      return committedCandidate;
    }

    public AccountGameplayDelegationPendingIdentity identity() {
      return committedCandidate.identity();
    }

    long tokenIdentityFence() {
      return 1L;
    }

    String tokenProfile() {
      return GameSessionAccountDelegationProfile.PROFILE;
    }
  }
}
