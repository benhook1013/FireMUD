package net.firedevops.firemud.common.security;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A finite public-only RS256 key cache bound to one owner-supplied, authenticated JWKS source.
 *
 * <p>The source and its identity pin are deliberately injected by the owning service. This class
 * does not discover endpoints, read Kubernetes credentials, or establish signer readiness. A
 * verified key is only cryptographic evidence; callers still have to prove active registry state
 * and current Account authority independently.
 */
public final class AccountPublicJwksCache {
  public static final int MAX_JWKS_BYTES = 256 * 1024;
  public static final int MAX_KEYS = 64;
  private static final int MAX_REMEMBERED_KIDS = 256;
  private static final int MAX_UNKNOWN_KID_REFRESHES_PER_SNAPSHOT = 256;
  private static final Duration MIN_UNKNOWN_KID_REFRESH_INTERVAL = Duration.ofSeconds(1);
  private static final Duration STALE_SOURCE_RETRY_BACKOFF = Duration.ofSeconds(1);
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern CLUSTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final TrustedPublicJwksSource source;
  private final SourceIdentity expectedSource;
  private final Clock clock;
  private final Duration maximumAge;
  private final Object monitor = new Object();
  private final Map<String, String> rememberedFingerprints = new LinkedHashMap<>();
  private final Set<String> hardDeniedKids = new LinkedHashSet<>();

  private Map<String, RSAPublicKey> keys = Map.of();
  private boolean hardCutoverQuarantined;
  private Instant loadedAt;
  private String loadedJwksFingerprint;
  private Instant lastUnknownKidRefreshAt;
  private Instant lastStaleSourceUnavailableAt;
  private final Set<String> unknownKidsRefreshedForSnapshot = new LinkedHashSet<>();

  public AccountPublicJwksCache(
      TrustedPublicJwksSource source,
      SourceIdentity expectedSource,
      Clock clock,
      Duration maximumAge) {
    this.source = Objects.requireNonNull(source, "trusted public JWKS source is required");
    this.expectedSource = Objects.requireNonNull(expectedSource, "source pin is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.maximumAge = Objects.requireNonNull(maximumAge, "maximum cache age is required");
    if (maximumAge.isNegative()
        || maximumAge.isZero()
        || maximumAge.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Account public JWKS cache age is outside its bound");
    }
  }

  /**
   * Returns a currently usable key; unknown-kid lookups can trigger at most one refresh per second.
   */
  public RSAPublicKey keyFor(String kid) {
    if (kid == null || !KID.matcher(kid).matches()) {
      throw new UnknownKeyException();
    }
    synchronized (monitor) {
      rejectHardDenied(kid);
      Instant now = clock.instant();
      boolean cachedKidKnown = keys.containsKey(kid);
      if (!cachedKidKnown && loadedAt != null) {
        if (now.isBefore(loadedAt) || !unknownKidRefreshEligible(now)) {
          throw new UnknownKeyException();
        }
      }
      if (!isFresh(now)
          && lastStaleSourceUnavailableAt != null
          && !now.isBefore(lastStaleSourceUnavailableAt)
          && Duration.between(lastStaleSourceUnavailableAt, now)
                  .compareTo(STALE_SOURCE_RETRY_BACKOFF)
              < 0) {
        if (unknownKidsRefreshedForSnapshot.contains(kid)) {
          throw new UnknownKeyException();
        }
        throw new SourceUnavailableException();
      }
      if (isFresh(now)) {
        RSAPublicKey existing = keys.get(kid);
        if (existing != null) {
          return existing;
        }
        if (!unknownKidsRefreshedForSnapshot.contains(kid)
            && unknownKidsRefreshedForSnapshot.size() < MAX_UNKNOWN_KID_REFRESHES_PER_SNAPSHOT) {
          recordUnknownKidRefresh(kid, now);
          refreshFromSource();
          rejectHardDenied(kid);
          RSAPublicKey refreshed = keys.get(kid);
          if (refreshed != null) {
            return refreshed;
          }
          if (unknownKidsRefreshedForSnapshot.size() < MAX_UNKNOWN_KID_REFRESHES_PER_SNAPSHOT) {
            unknownKidsRefreshedForSnapshot.add(kid);
          }
        }
        throw new UnknownKeyException();
      }

      try {
        if (!cachedKidKnown && loadedAt != null) {
          recordUnknownKidRefresh(kid, now);
        }
        refreshFromSource();
      } catch (SourceUnavailableException unavailable) {
        lastStaleSourceUnavailableAt = clock.instant();
        if (unknownKidsRefreshedForSnapshot.size() < MAX_UNKNOWN_KID_REFRESHES_PER_SNAPSHOT) {
          unknownKidsRefreshedForSnapshot.add(kid);
        }
        throw unavailable;
      }
      lastStaleSourceUnavailableAt = null;
      rejectHardDenied(kid);
      RSAPublicKey refreshed = keys.get(kid);
      if (refreshed == null) {
        if (unknownKidsRefreshedForSnapshot.size() < MAX_UNKNOWN_KID_REFRESHES_PER_SNAPSHOT) {
          unknownKidsRefreshedForSnapshot.add(kid);
        }
        throw new UnknownKeyException();
      }
      return refreshed;
    }
  }

  /**
   * Permanently denies this kid for this cache instance, including during source outages. If the
   * finite deny history is exhausted, the instance enters terminal fail-closed quarantine so no key
   * can be re-admitted without growing or evicting deny history.
   */
  public void invalidateKid(String kid) {
    if (kid == null || !KID.matcher(kid).matches()) {
      throw new IllegalArgumentException("Account public JWKS key identifier is invalid");
    }
    synchronized (monitor) {
      if (!hardDeniedKids.contains(kid) && hardDeniedKids.size() >= MAX_KEYS) {
        hardCutoverQuarantined = true;
        throw new IllegalStateException("Account public JWKS hard-cutover bound is exhausted");
      }
      hardDeniedKids.add(kid);
      Map<String, RSAPublicKey> remaining = new LinkedHashMap<>(keys);
      remaining.remove(kid);
      keys = Collections.unmodifiableMap(remaining);
    }
  }

  /** Exposes only the non-secret source pin for integration correspondence checks. */
  public SourceIdentity sourceIdentity() {
    return expectedSource;
  }

  private boolean isFresh(Instant now) {
    if (loadedAt == null || now.isBefore(loadedAt)) {
      return false;
    }
    return Duration.between(loadedAt, now).compareTo(maximumAge) < 0;
  }

  private boolean unknownKidRefreshEligible(Instant now) {
    return lastUnknownKidRefreshAt == null
        || (!now.isBefore(lastUnknownKidRefreshAt)
            && Duration.between(lastUnknownKidRefreshAt, now)
                    .compareTo(MIN_UNKNOWN_KID_REFRESH_INTERVAL)
                >= 0);
  }

  private void recordUnknownKidRefresh(String kid, Instant now) {
    lastUnknownKidRefreshAt = now;
    if (unknownKidsRefreshedForSnapshot.size() < MAX_UNKNOWN_KID_REFRESHES_PER_SNAPSHOT) {
      unknownKidsRefreshedForSnapshot.add(kid);
    }
  }

  private void refreshFromSource() {
    final PublicJwksSnapshot snapshot;
    try {
      snapshot = source.load();
    } catch (SourceUnavailableException unavailable) {
      if (loadedAt == null || !isFresh(clock.instant())) {
        throw new SourceUnavailableException();
      }
      return;
    } catch (RuntimeException invalidSource) {
      throw new InvalidJwksException();
    }
    if (snapshot == null || !expectedSource.equals(snapshot.sourceIdentity())) {
      throw new InvalidJwksException();
    }

    byte[] snapshotBytes = snapshot.jwksBytes();
    Map<String, RSAPublicKey> parsed = parseJwks(snapshotBytes);
    Map<String, String> nextFingerprints = new LinkedHashMap<>(rememberedFingerprints);
    for (Map.Entry<String, RSAPublicKey> entry : parsed.entrySet()) {
      String fingerprint = fingerprint(entry.getValue());
      String prior = nextFingerprints.putIfAbsent(entry.getKey(), fingerprint);
      if (prior != null && !prior.equals(fingerprint)) {
        throw new InvalidJwksException();
      }
      if (nextFingerprints.size() > MAX_REMEMBERED_KIDS) {
        throw new InvalidJwksException();
      }
    }
    rememberedFingerprints.clear();
    rememberedFingerprints.putAll(nextFingerprints);
    keys = Collections.unmodifiableMap(parsed);
    String nextJwksFingerprint = fingerprintBytes(snapshotBytes);
    if (!Objects.equals(loadedJwksFingerprint, nextJwksFingerprint)) {
      unknownKidsRefreshedForSnapshot.clear();
    }
    loadedJwksFingerprint = nextJwksFingerprint;
    loadedAt = clock.instant();
    lastStaleSourceUnavailableAt = null;
  }

  private void rejectHardDenied(String kid) {
    if (hardCutoverQuarantined || hardDeniedKids.contains(kid)) {
      throw new UnknownKeyException();
    }
  }

  private static Map<String, RSAPublicKey> parseJwks(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_JWKS_BYTES) {
      throw new InvalidJwksException();
    }
    try {
      String text = strictUtf8(bytes);
      Map<String, Object> root = JSON.readValue(text, OBJECT);
      requireExactFields(root, Set.of("keys"));
      if (!(root.get("keys") instanceof List<?> list) || list.isEmpty() || list.size() > MAX_KEYS) {
        throw new InvalidJwksException();
      }
      Map<String, RSAPublicKey> parsed = new LinkedHashMap<>();
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> raw)) {
          throw new InvalidJwksException();
        }
        Map<String, Object> key = stringKeyedMap(raw);
        Set<String> allowed = Set.of("kty", "use", "alg", "kid", "n", "e", "key_ops");
        if (!allowed.containsAll(key.keySet())
            || !key.keySet().containsAll(Set.of("kty", "use", "alg", "kid", "n", "e"))) {
          throw new InvalidJwksException();
        }
        if (!(key.get("kty") instanceof String kty)
            || !"RSA".equals(kty)
            || !(key.get("use") instanceof String use)
            || !"sig".equals(use)
            || !(key.get("alg") instanceof String alg)
            || !"RS256".equals(alg)
            || !(key.get("kid") instanceof String kid)
            || !KID.matcher(kid).matches()) {
          throw new InvalidJwksException();
        }
        if (key.containsKey("key_ops")
            && (!(key.get("key_ops") instanceof List<?> ops) || !ops.equals(List.of("verify")))) {
          throw new InvalidJwksException();
        }
        BigInteger modulus = decodeUnsigned(key.get("n"), 2048);
        BigInteger exponent = decodeUnsigned(key.get("e"), 8);
        if (modulus.bitLength() < 3072
            || modulus.bitLength() > 16384
            || !modulus.testBit(0)
            || !BigInteger.valueOf(65537).equals(exponent)) {
          throw new InvalidJwksException();
        }
        RSAPublicKey publicKey =
            (RSAPublicKey)
                KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        if (parsed.putIfAbsent(kid, publicKey) != null) {
          throw new InvalidJwksException();
        }
      }
      return parsed;
    } catch (InvalidJwksException ex) {
      throw ex;
    } catch (GeneralSecurityException | RuntimeException ex) {
      throw new InvalidJwksException();
    }
  }

  private static BigInteger decodeUnsigned(Object encodedValue, int maximumBytes) {
    if (!(encodedValue instanceof String encoded)
        || encoded.isEmpty()
        || encoded.indexOf('=') >= 0
        || !encoded.matches("[A-Za-z0-9_-]+")) {
      throw new InvalidJwksException();
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(encoded);
      if (decoded.length == 0
          || decoded.length > maximumBytes
          || decoded[0] == 0
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
        throw new InvalidJwksException();
      }
      return new BigInteger(1, decoded);
    } catch (IllegalArgumentException ex) {
      throw new InvalidJwksException();
    }
  }

  private static void requireExactFields(Map<String, ?> object, Set<String> expected) {
    if (object == null || !object.keySet().equals(expected)) {
      throw new InvalidJwksException();
    }
  }

  private static Map<String, Object> stringKeyedMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || result.putIfAbsent(key, entry.getValue()) != null) {
        throw new InvalidJwksException();
      }
    }
    return result;
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw new InvalidJwksException();
    }
  }

  private static String fingerprint(RSAPublicKey key) {
    try {
      byte[] material =
          (key.getModulus().toString(16) + ":" + key.getPublicExponent().toString(16))
              .getBytes(StandardCharsets.US_ASCII);
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(material));
    } catch (Exception ex) {
      throw new InvalidJwksException();
    }
  }

  private static String fingerprintBytes(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception ex) {
      throw new InvalidJwksException();
    }
  }

  /** Exact protected API/cluster/resource identity expected from every source read. */
  public record SourceIdentity(
      String environmentId,
      String clusterId,
      String clusterIncarnationUid,
      String namespace,
      String namespaceUid,
      String configMapUid,
      String bindingRevision,
      String apiServerOrigin,
      String servingCaSha256) {
    public SourceIdentity {
      if (environmentId == null
          || !NAMESPACE.matcher(environmentId).matches()
          || clusterId == null
          || !CLUSTER.matcher(clusterId).matches()
          || clusterIncarnationUid == null
          || !UID.matcher(clusterIncarnationUid).matches()
          || namespace == null
          || !NAMESPACE.matcher(namespace).matches()
          || namespaceUid == null
          || !UID.matcher(namespaceUid).matches()
          || configMapUid == null
          || !UID.matcher(configMapUid).matches()
          || bindingRevision == null
          || !CLUSTER.matcher(bindingRevision).matches()
          || apiServerOrigin == null
          || !validApiOrigin(apiServerOrigin)
          || servingCaSha256 == null
          || !SHA256.matcher(servingCaSha256).matches()) {
        throw new IllegalArgumentException("Account public JWKS source identity is invalid");
      }
    }

    private static boolean validApiOrigin(String value) {
      try {
        URI uri = URI.create(value);
        return "https".equalsIgnoreCase(uri.getScheme())
            && uri.getHost() != null
            && uri.getPort() > 0
            && uri.getRawUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && (uri.getRawPath() == null
                || uri.getRawPath().isEmpty()
                || "/".equals(uri.getRawPath()));
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
  }

  /** Owner-provided authenticated retrieval seam; implementations must verify their trust pins. */
  @FunctionalInterface
  public interface TrustedPublicJwksSource {
    PublicJwksSnapshot load() throws SourceUnavailableException;
  }

  /** A single exact public ConfigMap data value plus its independently established source pin. */
  public record PublicJwksSnapshot(SourceIdentity sourceIdentity, byte[] jwksBytes) {
    public PublicJwksSnapshot {
      Objects.requireNonNull(sourceIdentity, "source identity is required");
      jwksBytes = jwksBytes == null ? null : jwksBytes.clone();
    }

    @Override
    public byte[] jwksBytes() {
      return jwksBytes == null ? null : jwksBytes.clone();
    }
  }

  public static final class SourceUnavailableException extends RuntimeException {
    public SourceUnavailableException() {
      super("Account public JWKS source is unavailable");
    }
  }

  public static final class InvalidJwksException extends RuntimeException {
    public InvalidJwksException() {
      super("Account public JWKS source data is invalid");
    }
  }

  public static final class UnknownKeyException extends RuntimeException {
    public UnknownKeyException() {
      super("Account public JWKS key is unavailable");
    }
  }
}
