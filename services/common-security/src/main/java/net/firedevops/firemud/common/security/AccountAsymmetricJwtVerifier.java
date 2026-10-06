package net.firedevops.firemud.common.security;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict RS256 compact-JWT verification for an explicitly selected route/profile policy.
 *
 * <p>The result is immutable validated claims only. It is not an authenticated principal, admission
 * decision, SecurityContext, active-registry proof, or current Account authority proof.
 */
public final class AccountAsymmetricJwtVerifier {
  private static final int MAX_HEADER_BYTES = 2 * 1024;
  private static final int MAX_CLAIMS_BYTES = 12 * 1024;
  private static final int MAX_COMPACT_TOKEN_BYTES = 16 * 1024;
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final AccountPublicJwksCache keyCache;
  private final Clock clock;

  public AccountAsymmetricJwtVerifier(AccountPublicJwksCache keyCache, Clock clock) {
    this.keyCache = Objects.requireNonNull(keyCache, "Account public JWKS cache is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /** Verifies a compact RS256 signature and exact route policy without granting authority. */
  public VerifiedClaims verify(String compactJwt, ExplicitRouteProfilePolicy policy) {
    Objects.requireNonNull(policy, "explicit route/profile policy is required");
    try {
      CompactParts parts = splitCompact(compactJwt, policy.maxCompactTokenBytes());
      Map<String, Object> header = parseObject(parts.header(), MAX_HEADER_BYTES);
      String keyId = validateHeader(header);
      Map<String, Object> claims = parseObject(parts.claims(), MAX_CLAIMS_BYTES);
      validateClaims(claims, policy, clock.instant());

      RSAPublicKey key = keyCache.keyFor(keyId);
      Jws<Claims> verified =
          Jwts.parser()
              .verifyWith(key)
              .clock(() -> Date.from(clock.instant()))
              .clockSkewSeconds(policy.allowedClockSkewSeconds())
              .build()
              .parseSignedClaims(compactJwt);
      if (!"RS256".equals(verified.getHeader().getAlgorithm())
          || !keyId.equals(verified.getHeader().getKeyId())) {
        throw new VerificationException();
      }
      Map<String, Object> immutableClaims = deepImmutableMap(claims);
      policy.claimShapeValidator().validate(immutableClaims);
      return new VerifiedClaims(
          policy.routeId(), policy.profile(), policy.tokenType(), keyId, immutableClaims);
    } catch (AccountPublicJwksCache.SourceUnavailableException ex) {
      throw new VerificationUnavailableException();
    } catch (VerificationException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      // Do not retain token, source, JWK, or parser details in externally visible failures.
      throw new VerificationException();
    }
  }

  private static CompactParts splitCompact(String compactJwt, int maximumBytes) {
    if (compactJwt == null
        || compactJwt.isEmpty()
        || compactJwt.length() > maximumBytes
        || compactJwt.length() > MAX_COMPACT_TOKEN_BYTES) {
      throw new VerificationException();
    }
    for (int index = 0; index < compactJwt.length(); index++) {
      if (compactJwt.charAt(index) > 0x7f) {
        throw new VerificationException();
      }
    }
    String[] parts = compactJwt.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
      throw new VerificationException();
    }
    return new CompactParts(
        decodeSegment(parts[0]), decodeSegment(parts[1]), decodeSegment(parts[2]));
  }

  private static byte[] decodeSegment(String segment) {
    if (!segment.matches("[A-Za-z0-9_-]+")) {
      throw new VerificationException();
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(segment);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
        throw new VerificationException();
      }
      return decoded;
    } catch (IllegalArgumentException ex) {
      throw new VerificationException();
    }
  }

  private static String validateHeader(Map<String, Object> header) {
    if (!Set.of("alg", "kid", "typ").containsAll(header.keySet())
        || !header.keySet().containsAll(Set.of("alg", "kid"))
        || !"RS256".equals(header.get("alg"))
        || !(header.get("kid") instanceof String kid)
        || !KID.matcher(kid).matches()
        || (header.containsKey("typ") && !"JWT".equals(header.get("typ")))) {
      throw new VerificationException();
    }
    return kid;
  }

  private static Map<String, Object> parseObject(byte[] bytes, int maximumBytes) {
    if (bytes.length == 0 || bytes.length > maximumBytes) {
      throw new VerificationException();
    }
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      Map<String, Object> parsed = JSON.readValue(json, OBJECT);
      if (parsed == null) {
        throw new VerificationException();
      }
      return parsed;
    } catch (VerificationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new VerificationException();
    }
  }

  private static void validateClaims(
      Map<String, Object> claims, ExplicitRouteProfilePolicy policy, Instant now) {
    if (!policy.allowedClaims().containsAll(claims.keySet())
        || !claims.keySet().containsAll(policy.requiredClaims())
        || !policy.issuer().equals(claims.get("iss"))
        || !policy.audience().equals(claims.get("aud"))) {
      throw new VerificationException();
    }
    long issuedAt = positiveIntegerClaim(claims, "iat");
    long notBefore = positiveIntegerClaim(claims, "nbf");
    long expiresAt = positiveIntegerClaim(claims, "exp");
    long nowSeconds = now.getEpochSecond();
    long skew = policy.allowedClockSkewSeconds();
    if (issuedAt > safeAdd(nowSeconds, skew)
        || notBefore > safeAdd(nowSeconds, skew)
        || expiresAt <= safeSubtract(nowSeconds, skew)
        || notBefore > expiresAt
        || issuedAt >= expiresAt
        || safeSubtract(notBefore, issuedAt) > skew
        || safeSubtract(expiresAt, issuedAt) > policy.maximumLifetimeSeconds()) {
      throw new VerificationException();
    }

    requireNonEmptyText(claims, "sub");
    requireNonEmptyText(claims, "jti");
    if (claims.containsKey("accountId")) {
      requireNonEmptyText(claims, "accountId");
    }
    if (claims.containsKey("tokenGeneration")) {
      AccountJwtExactValues.positiveDecimalCounter(claims.get("tokenGeneration"));
    }
    if (claims.containsKey("issuanceFence")) {
      AccountJwtExactValues.positiveDecimalCounter(claims.get("issuanceFence"));
    }
    if (claims.containsKey("authorityTuple")
        && !(claims.get("authorityTuple") instanceof Map<?, ?>)) {
      throw new VerificationException();
    }
    if (claims.containsKey("membershipVersion")
        && !(claims.get("membershipVersion") instanceof Map<?, ?>)) {
      throw new VerificationException();
    }
  }

  private static long positiveIntegerClaim(Map<String, Object> claims, String field) {
    Object value = claims.get(field);
    if (!(value instanceof Number number)) {
      throw new VerificationException();
    }
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      if (result <= 0L) {
        throw new VerificationException();
      }
      return result;
    } catch (ArithmeticException | NumberFormatException ex) {
      throw new VerificationException();
    }
  }

  private static void requireNonEmptyText(Map<String, Object> claims, String field) {
    if (!(claims.get(field) instanceof String value) || value.isBlank() || value.length() > 512) {
      throw new VerificationException();
    }
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ex) {
      throw new VerificationException();
    }
  }

  private static long safeSubtract(long left, long right) {
    try {
      return Math.subtractExact(left, right);
    } catch (ArithmeticException ex) {
      throw new VerificationException();
    }
  }

  private static Map<String, Object> deepImmutableMap(Map<String, Object> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new VerificationException();
        }
        copy.put(key, immutableValue(entry.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(immutableValue(item));
      }
      return Collections.unmodifiableList(copy);
    }
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    throw new VerificationException();
  }

  /** Explicit, caller-owned policy for one route and one token profile/type pair. */
  public record ExplicitRouteProfilePolicy(
      String routeId,
      String profile,
      String tokenType,
      String issuer,
      String audience,
      Set<String> requiredClaims,
      Set<String> optionalClaims,
      long maximumLifetimeSeconds,
      long allowedClockSkewSeconds,
      int maxCompactTokenBytes,
      ClaimShapeValidator claimShapeValidator) {
    public ExplicitRouteProfilePolicy {
      if (routeId == null
          || routeId.isBlank()
          || profile == null
          || profile.isBlank()
          || tokenType == null
          || tokenType.isBlank()
          || issuer == null
          || issuer.isBlank()
          || audience == null
          || audience.isBlank()
          || requiredClaims == null
          || optionalClaims == null
          || claimShapeValidator == null
          || maximumLifetimeSeconds <= 0L
          || allowedClockSkewSeconds < 0L
          || allowedClockSkewSeconds > 60L
          || maxCompactTokenBytes <= 0
          || maxCompactTokenBytes > MAX_COMPACT_TOKEN_BYTES) {
        throw new IllegalArgumentException("Account JWT route profile policy is invalid");
      }
      Set<String> required = Set.copyOf(requiredClaims);
      Set<String> optional = Set.copyOf(optionalClaims);
      if (required.isEmpty()
          || !required.containsAll(Set.of("iss", "aud", "sub", "jti", "iat", "nbf", "exp"))
          || !Collections.disjoint(required, optional)
          || required.stream().anyMatch(value -> value == null || value.isBlank())
          || optional.stream().anyMatch(value -> value == null || value.isBlank())) {
        throw new IllegalArgumentException("Account JWT route profile policy is invalid");
      }
      requiredClaims = Collections.unmodifiableSet(new LinkedHashSet<>(required));
      optionalClaims = Collections.unmodifiableSet(new LinkedHashSet<>(optional));
    }

    public Set<String> allowedClaims() {
      Set<String> result = new LinkedHashSet<>(requiredClaims);
      result.addAll(optionalClaims);
      return Collections.unmodifiableSet(result);
    }
  }

  /** Exact profile-shape validation performed only after the compact signature is verified. */
  @FunctionalInterface
  public interface ClaimShapeValidator {
    void validate(Map<String, Object> immutableClaims);
  }

  /** Immutable claims and route metadata; intentionally carries no principal or authority. */
  public record VerifiedClaims(
      String routeId, String profile, String tokenType, String keyId, Map<String, Object> claims) {
    public VerifiedClaims {
      Objects.requireNonNull(routeId, "route ID is required");
      Objects.requireNonNull(profile, "profile metadata is required");
      Objects.requireNonNull(tokenType, "token type metadata is required");
      Objects.requireNonNull(keyId, "verified key ID is required");
      claims = deepImmutableMap(Objects.requireNonNull(claims, "verified claims are required"));
    }

    @Override
    public Map<String, Object> claims() {
      return deepImmutableMap(claims);
    }
  }

  public static final class VerificationException extends RuntimeException {
    public VerificationException() {
      super("Account JWT verification failed");
    }
  }

  /** A retryable trust-source outage, distinct from invalid or revoked token evidence. */
  public static final class VerificationUnavailableException extends RuntimeException {
    public VerificationUnavailableException() {
      super("Account JWT verification is unavailable");
    }
  }

  private record CompactParts(byte[] header, byte[] claims, byte[] signature) {}
}
