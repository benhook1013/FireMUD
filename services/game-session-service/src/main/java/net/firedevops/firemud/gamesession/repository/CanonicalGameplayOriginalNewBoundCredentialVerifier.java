package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/**
 * Verifies the Account-signed, tenant-bound ORIGINAL_NEW credential and its exact lease anchors.
 *
 * <p>This checks signature and claim/source shape only. It does not prove current Account
 * authority, token-registry status, final admission, or gameplay admission. Those predicates stay
 * with their owning readers and the final session CAS.
 */
public final class CanonicalGameplayOriginalNewBoundCredentialVerifier {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long ALLOWED_CLOCK_SKEW_SECONDS = 5L;
  private static final ExplicitRouteProfilePolicy ORIGINAL_NEW_POLICY = policy();

  private final AccountAsymmetricJwtVerifier verifier;
  private final Clock clock;

  /** Requires the caller to supply the already configured trusted Account JWKS cache. */
  public CanonicalGameplayOriginalNewBoundCredentialVerifier(
      AccountPublicJwksCache trustedAccountJwks, Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.verifier =
        new AccountAsymmetricJwtVerifier(
            Objects.requireNonNull(trustedAccountJwks, "trusted Account JWKS cache is required"),
            clock);
  }

  VerifiedBoundCredential verify(
      CanonicalGameplayOriginalNewCandidate candidate,
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential accountCredential) {
    Objects.requireNonNull(candidate, "original NEW candidate is required");
    Objects.requireNonNull(accountCredential, "Account credential is required");
    byte[] compactBytes = null;
    byte[] handle = null;
    try {
      String compactJwt = accountCredential.signedBoundGsACompactJwt();
      if (compactJwt == null
          || compactJwt.isEmpty()
          || compactJwt.length() > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
          || compactJwt.chars().anyMatch(value -> value > 0x7f)) {
        throw invalid();
      }
      compactBytes = compactJwt.getBytes(StandardCharsets.US_ASCII);
      String tokenHash = sha256(compactBytes);
      if (!tokenHash.equals(accountCredential.tokenSha256())) throw invalid();

      VerifiedClaims verified = verifier.verify(compactJwt, ORIGINAL_NEW_POLICY);
      Map<String, Object> claims = verified.claims();
      Map<String, Object> lease = candidate.leaseEvidence().carrier();
      Map<?, ?> bindingScope = object(lease.get("bindingScope"));
      Map<?, ?> membershipBaseline = object(lease.get("membershipBaseline"));
      Map<?, ?> tokenIdentityEvidence = object(lease.get("tokenIdentityEvidence"));
      Object authorityTuple = lease.get("authorityTuple");
      Object checkpoints = lease.get("outboxCheckpoints");

      String accountId = candidate.bindingIdentity().accountId().toString();
      String tenantId = candidate.bindingIdentity().tenantId().toString();
      String initialJti = text(tokenIdentityEvidence.get("tokenJti"));
      String originalLeaseId = text(lease.get("leaseId"));
      String issuedFence = text(lease.get("issuanceFence"));
      String boundJti = accountCredential.tokenJti().toString();
      UUID lineageId = accountCredential.lineageId();
      long nowSeconds = clock.instant().getEpochSecond();
      long issuedAt = positiveEpochSecond(claims.get("iat"));
      long notBefore = positiveEpochSecond(claims.get("nbf"));
      long expiresAt = positiveEpochSecond(claims.get("exp"));

      if (!accountId.equals(bindingScope.get("accountId"))
          || !tenantId.equals(bindingScope.get("tenantId"))
          || !accountId.equals(claims.get("sub"))
          || !accountId.equals(claims.get("accountId"))
          || !tenantId.equals(claims.get("tenantId"))
          || !boundJti.equals(claims.get("jti"))
          || boundJti.equals(initialJti)
          || lineageId.equals(accountCredential.tokenJti())
          || !"1".equals(claims.get("tokenGeneration"))
          || !issuedFence.equals(claims.get("issuanceFence"))
          || accountCredential.issuanceFence() != positiveDecimal(issuedFence)
          || !originalLeaseId.equals(accountCredential.originalLeaseId().toString())
          || candidate.leaseEvidence().leaseFence().longValueExact()
              != accountCredential.originalLeaseFence()
          || !canonicalEquals(authorityTuple, claims.get("authorityTuple"))
          || !canonicalEquals(
              membershipBaseline.get("membershipVersion"), claims.get("membershipVersion"))
          || !candidate
              .leaseEvidence()
              .leaseKind()
              .equals(AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING)
          || !accountCredential.checkpointsSha256().equals(sha256Canonical(checkpoints))) {
        throw invalid();
      }

      String membershipGeneration = text(membershipBaseline.get("membershipAuthorityGeneration"));
      Map<?, ?> tuple = object(authorityTuple);
      Map<?, ?> membershipAuthority = object(tuple.get("membershipAuthorityGeneration"));
      if (!membershipGeneration.equals(membershipAuthority.get(tenantId))) throw invalid();

      if (issuedAt > safeAdd(nowSeconds, ALLOWED_CLOCK_SKEW_SECONDS)
          || notBefore > safeAdd(nowSeconds, ALLOWED_CLOCK_SKEW_SECONDS)
          || expiresAt <= nowSeconds
          || notBefore > expiresAt
          || issuedAt >= expiresAt
          || safeSubtract(notBefore, issuedAt) > ALLOWED_CLOCK_SKEW_SECONDS
          || safeSubtract(issuedAt, notBefore) > ALLOWED_CLOCK_SKEW_SECONDS
          || safeSubtract(expiresAt, issuedAt)
              > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
        throw invalid();
      }

      handle = accountCredential.opaqueRebindHandle();
      if (handle.length != 32) throw invalid();
      return new VerifiedBoundCredential(tokenHash, issuedAt, expiresAt, "1", issuedFence, handle);
    } catch (AccountAsymmetricJwtVerifier.VerificationUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException rejected) {
      throw invalid();
    } catch (Exception malformed) {
      throw invalid();
    } finally {
      wipe(compactBytes);
      wipe(handle);
    }
  }

  Clock clock() {
    return clock;
  }

  private static ExplicitRouteProfilePolicy policy() {
    Set<String> required =
        new HashSet<>(GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS);
    required.add("tenantId");
    return new ExplicitRouteProfilePolicy(
        "game-session-original-new-bound-credential",
        GameSessionAccountDelegationProfile.PROFILE,
        GameSessionAccountDelegationProfile.TYPE,
        GameSessionAccountDelegationProfile.ISSUER,
        GameSessionAccountDelegationProfile.AUDIENCE,
        required,
        Set.of(),
        GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
        ALLOWED_CLOCK_SKEW_SECONDS,
        GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
        GameSessionAccountDelegationJwtProfileValidator::validatePublicTenantBoundClaims);
  }

  private static boolean canonicalEquals(Object left, Object right) throws Exception {
    byte[] leftBytes = null;
    byte[] rightBytes = null;
    try {
      leftBytes = canonicalBytes(left);
      rightBytes = canonicalBytes(right);
      return Arrays.equals(leftBytes, rightBytes);
    } finally {
      wipe(leftBytes);
      wipe(rightBytes);
    }
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256Canonical(Object value) throws Exception {
    byte[] canonical = canonicalBytes(value);
    try {
      return sha256(canonical);
    } finally {
      wipe(canonical);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    return map;
  }

  private static String text(Object value) {
    if (!(value instanceof String text) || text.isBlank()) throw invalid();
    return text;
  }

  private static long positiveEpochSecond(Object value) {
    if (!(value instanceof Number number)) throw invalid();
    try {
      long seconds = new BigDecimal(number.toString()).longValueExact();
      if (seconds <= 0L) throw invalid();
      return seconds;
    } catch (ArithmeticException | NumberFormatException malformed) {
      throw invalid();
    }
  }

  private static long positiveDecimal(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException malformed) {
      throw invalid();
    }
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException malformed) {
      throw invalid();
    }
  }

  private static long safeSubtract(long left, long right) {
    try {
      return Math.subtractExact(left, right);
    } catch (ArithmeticException malformed) {
      throw invalid();
    }
  }

  private static void wipe(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Original NEW bound credential is invalid");
  }

  static final class VerifiedBoundCredential implements AutoCloseable {
    private final String tokenHash;
    private final long issuedAtEpochSeconds;
    private final long expiresAtEpochSeconds;
    private final String tokenGeneration;
    private final String issuanceFence;
    private byte[] opaqueRebindHandle;

    private VerifiedBoundCredential(
        String tokenHash,
        long issuedAtEpochSeconds,
        long expiresAtEpochSeconds,
        String tokenGeneration,
        String issuanceFence,
        byte[] opaqueRebindHandle) {
      this.tokenHash = tokenHash;
      this.issuedAtEpochSeconds = issuedAtEpochSeconds;
      this.expiresAtEpochSeconds = expiresAtEpochSeconds;
      this.tokenGeneration = tokenGeneration;
      this.issuanceFence = issuanceFence;
      this.opaqueRebindHandle = opaqueRebindHandle.clone();
    }

    String tokenHash() {
      return tokenHash;
    }

    long issuedAtEpochSeconds() {
      return issuedAtEpochSeconds;
    }

    long expiresAtEpochSeconds() {
      return expiresAtEpochSeconds;
    }

    String tokenGeneration() {
      return tokenGeneration;
    }

    String issuanceFence() {
      return issuanceFence;
    }

    byte[] opaqueRebindHandle() {
      if (opaqueRebindHandle == null)
        throw new IllegalStateException("Verified credential is closed");
      return opaqueRebindHandle.clone();
    }

    @Override
    public void close() {
      wipe(opaqueRebindHandle);
      opaqueRebindHandle = null;
    }

    @Override
    public String toString() {
      return "VerifiedBoundCredential[metadata-only,handle-redacted]";
    }
  }
}
