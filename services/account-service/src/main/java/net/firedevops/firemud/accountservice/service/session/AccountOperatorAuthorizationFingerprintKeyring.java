package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationReferenceFingerprint.ReferenceKind;

/**
 * Explicit Account-owned active/retained key snapshot for operator-reference fingerprints.
 *
 * <p>This class has no key source implementation, default key, or provisioning behavior. The caller
 * supplies an owner-controlled source and an explicit post-expiry reconciliation window; every
 * fingerprint operation reloads that source and fails closed for a missing, withdrawn, or
 * insufficiently retained key.
 */
public final class AccountOperatorAuthorizationFingerprintKeyring {
  private static final int MAX_RETAINED_KEYS = 8;
  private static final Pattern FINGERPRINT =
      Pattern.compile("arfp/v1/([A-Za-z0-9_-]{1,64})/([0-9a-f]{64})");
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final OwnerKeySource source;
  private final Clock clock;
  private final Duration requiredPostExpiryRetention;

  public AccountOperatorAuthorizationFingerprintKeyring(
      OwnerKeySource source, Clock clock, Duration requiredPostExpiryRetention) {
    this.source = Objects.requireNonNull(source, "explicit Account operator key source required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.requiredPostExpiryRetention =
        Objects.requireNonNull(requiredPostExpiryRetention, "retention policy is required");
    if (requiredPostExpiryRetention.isNegative() || requiredPostExpiryRetention.isZero()) {
      throw new IllegalArgumentException("Explicit positive reconciliation retention is required");
    }
  }

  /** Computes a new issuance fingerprint only with the currently declared active key. */
  public String fingerprintForIssue(ReferenceKind referenceKind, byte[] exactReferenceBytes) {
    Objects.requireNonNull(referenceKind, "reference kind is required");
    Snapshot current = requireSnapshot();
    return current.activeKey().calculator().fingerprint(referenceKind, exactReferenceBytes);
  }

  /**
   * Recomputes the original fingerprint using its exact stored key ID and verifies the supplied
   * value in constant time. Retired keys must remain available past both immutable expiries and the
   * explicitly configured reconciliation window.
   */
  public boolean matchesOriginal(
      ReferenceKind referenceKind,
      byte[] exactReferenceBytes,
      String originalFingerprint,
      Instant referenceExpiresAt,
      Instant responseEnvelopeExpiresAt) {
    Objects.requireNonNull(referenceKind, "reference kind is required");
    Objects.requireNonNull(referenceExpiresAt, "reference expiry is required");
    Objects.requireNonNull(responseEnvelopeExpiresAt, "response expiry is required");
    if (!responseEnvelopeExpiresAt.isAfter(referenceExpiresAt)) {
      throw new IllegalArgumentException("Original response expiry must follow reference expiry");
    }
    Matcher matcher = originalFingerprint == null ? null : FINGERPRINT.matcher(originalFingerprint);
    if (matcher == null || !matcher.matches()) {
      throw new IllegalArgumentException("Original Account reference fingerprint is malformed");
    }

    Snapshot current = requireSnapshot();
    String keyId = matcher.group(1);
    KeyMaterial key = current.keyFor(keyId);
    if (key == null) {
      throw new KeyUnavailableException();
    }
    if (key != current.activeKey()) {
      Instant now = clock.instant();
      if (!now.isBefore(key.retainedUntil())) {
        throw new KeyUnavailableException();
      }
      Instant requiredUntil;
      try {
        requiredUntil = responseEnvelopeExpiresAt.plus(requiredPostExpiryRetention);
      } catch (RuntimeException invalidHorizon) {
        throw new KeyUnavailableException();
      }
      if (!key.retainedUntil().isAfter(requiredUntil)) {
        throw new KeyUnavailableException();
      }
    }
    String actual = key.calculator().fingerprint(referenceKind, exactReferenceBytes);
    return MessageDigest.isEqual(
        actual.getBytes(StandardCharsets.US_ASCII),
        originalFingerprint.getBytes(StandardCharsets.US_ASCII));
  }

  private Snapshot requireSnapshot() {
    try {
      return Objects.requireNonNull(source.readCurrent(), "owner key snapshot is required");
    } catch (KeyUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException unavailable) {
      throw new KeyUnavailableException();
    }
  }

  @FunctionalInterface
  public interface OwnerKeySource {
    /** Reads the owner-controlled active and retained key snapshot afresh for this operation. */
    Snapshot readCurrent();
  }

  /** One active key and at most eight explicitly retained prior keys. */
  public record Snapshot(KeyMaterial activeKey, List<KeyMaterial> retainedKeys) {
    public Snapshot {
      Objects.requireNonNull(activeKey, "one active Account operator key is required");
      if (activeKey.retainedUntil() != null) {
        throw new IllegalArgumentException("Active Account operator key cannot be marked retained");
      }
      retainedKeys =
          List.copyOf(Objects.requireNonNull(retainedKeys, "retained keys are required"));
      if (retainedKeys.size() > MAX_RETAINED_KEYS) {
        throw new IllegalArgumentException("Retained Account operator keys exceed their bound");
      }
      Set<String> keyIds = new HashSet<>();
      if (!keyIds.add(activeKey.keyId())) {
        throw new IllegalArgumentException("Active Account operator key ID is duplicated");
      }
      for (KeyMaterial retained : retainedKeys) {
        Objects.requireNonNull(retained, "retained Account operator key is required");
        if (retained.retainedUntil() == null || !keyIds.add(retained.keyId())) {
          throw new IllegalArgumentException("Retained Account operator key is malformed");
        }
      }
    }

    private KeyMaterial keyFor(String keyId) {
      if (activeKey.keyId().equals(keyId)) {
        return activeKey;
      }
      return retainedKeys.stream()
          .filter(value -> value.keyId().equals(keyId))
          .findFirst()
          .orElse(null);
    }

    @Override
    public String toString() {
      return "AccountOperatorAuthorizationFingerprintKeyring.Snapshot[key material redacted]";
    }
  }

  /** Secret material remains private; only its bounded identifier is exposed. */
  public static final class KeyMaterial {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String keyId;
    private final AccountOperatorAuthorizationReferenceFingerprint calculator;
    private final Instant retainedUntil;

    public KeyMaterial(String keyId, SecretKey secretKey) {
      this(keyId, secretKey, null);
    }

    public KeyMaterial(String keyId, SecretKey secretKey, Instant retainedUntil) {
      if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
        throw new IllegalArgumentException("Unsupported Account operator key ID");
      }
      Objects.requireNonNull(secretKey, "explicit Account operator key material is required");
      if (!HMAC_ALGORITHM.equalsIgnoreCase(secretKey.getAlgorithm())) {
        throw new IllegalArgumentException("Unsupported Account operator key algorithm");
      }
      byte[] encoded = secretKey.getEncoded();
      if (encoded == null || encoded.length == 0 || encoded.length > 4096) {
        throw new IllegalArgumentException("Unsupported Account operator key material");
      }
      byte[] copy = encoded.clone();
      try {
        SecretKey immutableCopy = new SecretKeySpec(copy, HMAC_ALGORITHM);
        calculator = new AccountOperatorAuthorizationReferenceFingerprint(keyId, immutableCopy);
      } finally {
        java.util.Arrays.fill(copy, (byte) 0);
      }
      if (retainedUntil != null && retainedUntil.toEpochMilli() <= 0L) {
        throw new IllegalArgumentException("Retained Account operator key expiry is invalid");
      }
      this.keyId = keyId;
      this.retainedUntil = retainedUntil;
    }

    public String keyId() {
      return keyId;
    }

    private AccountOperatorAuthorizationReferenceFingerprint calculator() {
      return calculator;
    }

    private Instant retainedUntil() {
      return retainedUntil;
    }

    @Override
    public String toString() {
      return "AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial[key material redacted]";
    }
  }

  /** Fail-closed result for an absent, withdrawn, expired, or insufficiently retained key. */
  public static final class KeyUnavailableException extends RuntimeException {
    public KeyUnavailableException() {
      super("Account operator authorization fingerprint key is unavailable");
    }
  }
}
