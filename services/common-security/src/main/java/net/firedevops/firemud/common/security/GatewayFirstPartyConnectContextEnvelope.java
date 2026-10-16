package net.firedevops.firemud.common.security;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical Gateway-owned JWS envelope for the protected first-party gameplay handoff.
 *
 * <p>This codec validates a typed source projection and signs the context; it does not establish
 * that the source token, replay record, Gateway key delivery, or transport was qualified. A live
 * producer must supply claims obtained from the canonical protected route and Gateway-derived
 * address metadata. The verified context is not a Game Session admission decision.
 */
public final class GatewayFirstPartyConnectContextEnvelope {
  public static final String PROTECTED_TYPE = "application/firemud-connect-context.v1+jws";
  public static final String AUDIENCE = "game-session";
  public static final String RECIPIENT = "game-session-service";
  public static final String TRANSPORT_CLASS = "first_party_web";

  private static final int MAX_HEADER_BYTES = 2 * 1024;
  private static final int MAX_PAYLOAD_BYTES = 12 * 1024;
  private static final int MAX_COMPACT_BYTES = 16 * 1024;
  private static final int MAX_CLOCK_SKEW_MILLIS = 5_000;
  private static final long MAX_LIFETIME_MILLIS = 30_000L;
  private static final long JCS_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final BigInteger MAX_POSITIVE_BIGINT = BigInteger.valueOf(Long.MAX_VALUE);
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._:-]{1,256}");
  private static final Set<String> AUTHORITY_REQUIRED_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> ACCOUNT_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> TENANT_CUTOFF_FIELDS =
      Set.of(
          "tenantAuthorityGeneration",
          "tenantBillingSequence",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> GRANT_FIELDS =
      Set.of("tenantId", "worldSlug", "realmSlug", "playtestLifecycleId", "grantVersion");
  private static final Set<String> CONTEXT_REQUIRED_FIELDS =
      Set.of(
          "accountId",
          "tenantId",
          "realmId",
          "worldSlug",
          "realmSlug",
          "playableStateNamespaceId",
          "playableStateScope",
          "gameInstanceId",
          "pointerVersion",
          "catalogRevision",
          "connectScopeId",
          "connectRequestId",
          "audience",
          "recipient",
          "authorityTuple",
          "membershipVersion",
          "connectTokenJti",
          "replayAdmissionFence",
          "issuedAt",
          "verifiedAt",
          "expiresAt",
          "gatewayRequestId",
          "canonicalClientAddress",
          "transportClass");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private GatewayFirstPartyConnectContextEnvelope() {}

  /** Signs one complete typed source projection with a dedicated Gateway private key. */
  public static String sign(
      GameplayConnectSource source, GatewayContextMetadata metadata, GatewaySigningKey signingKey) {
    Objects.requireNonNull(source, "gameplay-connect source is required");
    Objects.requireNonNull(metadata, "Gateway context metadata is required");
    Objects.requireNonNull(signingKey, "Gateway signing key is required");
    validateLifetime(
        metadata.issuedAtMillis(),
        metadata.verifiedAtMillis(),
        metadata.expiresAtMillis(),
        source.sourceExpiresAtEpochSeconds(),
        metadata.issuedAtMillis(),
        0);

    Map<String, Object> header =
        Map.of("alg", "RS256", "kid", signingKey.keyId(), "typ", PROTECTED_TYPE);
    ContextClaims context = ContextClaims.from(source, metadata);
    requireAuthorityTupleBound(context.authorityTuple().toMap());
    byte[] headerBytes = canonicalJson(header);
    byte[] payloadBytes = canonicalJson(context.toMap());
    requireEncodedBounds(headerBytes, payloadBytes);

    String encodedHeader = encode(headerBytes);
    String encodedPayload = encode(payloadBytes);
    String signingInput = encodedHeader + "." + encodedPayload;
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(signingKey.privateKey);
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      String compact = signingInput + "." + encode(signer.sign());
      if (compact.length() > MAX_COMPACT_BYTES) {
        throw new IllegalArgumentException("Connect context exceeds its compact size bound");
      }
      return compact;
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Gateway connect-context signing failed", failure);
    }
  }

  /** Verifies canonical encoding, the dedicated Gateway key, exact schema, and expiry bounds. */
  public static VerifiedContext verify(
      String compactJws,
      GatewayVerificationKeySource keySource,
      long nowMillis,
      int allowedClockSkewMillis) {
    Objects.requireNonNull(keySource, "Gateway verification-key source is required");
    if (nowMillis <= 0L
        || allowedClockSkewMillis < 0
        || allowedClockSkewMillis > MAX_CLOCK_SKEW_MILLIS) {
      throw reject(FailureReason.INVALID_LIFETIME);
    }
    CompactParts parts = splitCompact(compactJws);
    Map<String, Object> header = parseObject(parts.header(), MAX_HEADER_BYTES);
    String keyId = validateHeader(header);
    byte[] canonicalHeader = canonicalJson(header);
    if (!java.util.Arrays.equals(parts.header(), canonicalHeader)) {
      throw reject(FailureReason.NON_CANONICAL_ENCODING);
    }

    RSAPublicKey verificationKey;
    try {
      verificationKey = keySource.publicKeyFor(keyId);
    } catch (KeySourceUnavailableException unavailable) {
      throw reject(FailureReason.KEY_SOURCE_UNAVAILABLE);
    } catch (RuntimeException unavailable) {
      throw reject(FailureReason.KEY_SOURCE_UNAVAILABLE);
    }
    if (verificationKey == null) {
      throw reject(FailureReason.UNKNOWN_KEY_ID);
    }
    try {
      requireRsaKey(verificationKey);
    } catch (IllegalArgumentException invalidKey) {
      throw reject(FailureReason.INVALID_SIGNATURE);
    }
    verifySignature(parts, verificationKey);

    Map<String, Object> payload = parseObject(parts.payload(), MAX_PAYLOAD_BYTES);
    byte[] canonicalPayload = canonicalJson(payload);
    if (!java.util.Arrays.equals(parts.payload(), canonicalPayload)) {
      throw reject(FailureReason.NON_CANONICAL_ENCODING);
    }
    ContextClaims context;
    try {
      context = ContextClaims.fromMap(payload);
    } catch (VerificationException failure) {
      throw failure;
    } catch (RuntimeException invalidPayload) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    validateLifetime(
        context.issuedAtMillis(),
        context.verifiedAtMillis(),
        context.expiresAtMillis(),
        0L,
        nowMillis,
        allowedClockSkewMillis);
    return new VerifiedContext(keyId, context);
  }

  private static void verifySignature(CompactParts parts, RSAPublicKey key) {
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(parts.signingInput().getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(parts.signature())) {
        throw reject(FailureReason.INVALID_SIGNATURE);
      }
    } catch (VerificationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw reject(FailureReason.INVALID_SIGNATURE);
    }
  }

  private static String validateHeader(Map<String, Object> header) {
    if (!header.keySet().equals(Set.of("alg", "kid", "typ"))
        || !"RS256".equals(header.get("alg"))
        || !PROTECTED_TYPE.equals(header.get("typ"))
        || !(header.get("kid") instanceof String keyId)
        || !KEY_ID.matcher(keyId).matches()) {
      throw reject(FailureReason.INVALID_HEADER);
    }
    return keyId;
  }

  private static CompactParts splitCompact(String compact) {
    if (compact == null || compact.isEmpty() || compact.length() > MAX_COMPACT_BYTES) {
      throw reject(FailureReason.MALFORMED_COMPACT);
    }
    for (int index = 0; index < compact.length(); index++) {
      if (compact.charAt(index) > 0x7f) {
        throw reject(FailureReason.MALFORMED_COMPACT);
      }
    }
    String[] segments = compact.split("\\.", -1);
    if (segments.length != 3
        || segments[0].isEmpty()
        || segments[1].isEmpty()
        || segments[2].isEmpty()) {
      throw reject(FailureReason.MALFORMED_COMPACT);
    }
    byte[] header = decode(segments[0]);
    byte[] payload = decode(segments[1]);
    byte[] signature = decode(segments[2]);
    if (header.length > MAX_HEADER_BYTES || payload.length > MAX_PAYLOAD_BYTES) {
      throw reject(FailureReason.MALFORMED_COMPACT);
    }
    return new CompactParts(header, payload, signature, segments[0] + "." + segments[1]);
  }

  private static byte[] decode(String segment) {
    if (!segment.matches("[A-Za-z0-9_-]+")) {
      throw reject(FailureReason.MALFORMED_COMPACT);
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(segment);
      if (!encode(decoded).equals(segment)) {
        throw reject(FailureReason.MALFORMED_COMPACT);
      }
      return decoded;
    } catch (IllegalArgumentException failure) {
      throw reject(FailureReason.MALFORMED_COMPACT);
    }
  }

  private static Map<String, Object> parseObject(byte[] bytes, int maximumBytes) {
    if (bytes.length == 0 || bytes.length > maximumBytes) {
      throw reject(FailureReason.MALFORMED_JSON);
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
        throw reject(FailureReason.MALFORMED_JSON);
      }
      return parsed;
    } catch (VerificationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw reject(FailureReason.MALFORMED_JSON);
    }
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException failure) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static void requireEncodedBounds(byte[] header, byte[] payload) {
    if (header.length == 0
        || header.length > MAX_HEADER_BYTES
        || payload.length == 0
        || payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("Connect context exceeds its encoded size bound");
    }
  }

  private static void requireAuthorityTupleBound(Map<String, Object> authorityTuple) {
    if (canonicalJson(authorityTuple).length
        > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static void validateLifetime(
      long issuedAt,
      long verifiedAt,
      long expiresAt,
      long sourceExpiresAtEpochSeconds,
      long nowMillis,
      int allowedClockSkewMillis) {
    try {
      BigInteger sourceExpiryMillis =
          sourceExpiresAtEpochSeconds > 0L
              ? BigInteger.valueOf(sourceExpiresAtEpochSeconds).multiply(BigInteger.valueOf(1_000L))
              : null;
      long latestAllowedIssue = Math.addExact(nowMillis, allowedClockSkewMillis);
      long earliestAllowedExpiry = Math.subtractExact(nowMillis, allowedClockSkewMillis);
      long lifetime = Math.subtractExact(expiresAt, issuedAt);
      if (issuedAt <= 0L
          || issuedAt > JCS_MAX_SAFE_INTEGER
          || verifiedAt <= 0L
          || verifiedAt > JCS_MAX_SAFE_INTEGER
          || expiresAt <= 0L
          || expiresAt > JCS_MAX_SAFE_INTEGER
          || nowMillis > JCS_MAX_SAFE_INTEGER
          || sourceExpiresAtEpochSeconds < 0L
          || verifiedAt > issuedAt
          || issuedAt > latestAllowedIssue
          || expiresAt <= issuedAt
          || lifetime > MAX_LIFETIME_MILLIS
          || sourceExpiryMillis != null
              && BigInteger.valueOf(expiresAt).compareTo(sourceExpiryMillis) > 0
          || expiresAt <= earliestAllowedExpiry) {
        throw reject(FailureReason.INVALID_LIFETIME);
      }
    } catch (ArithmeticException overflow) {
      throw reject(FailureReason.INVALID_LIFETIME);
    }
  }

  private static String requiredString(Map<String, Object> map, String field, int maxUtf8Bytes) {
    Object value = map.get(field);
    if (!(value instanceof String text)) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    requireText(text, maxUtf8Bytes, field);
    return text;
  }

  private static String optionalString(Map<String, Object> map, String field, int maxUtf8Bytes) {
    if (!map.containsKey(field)) {
      return null;
    }
    return requiredString(map, field, maxUtf8Bytes);
  }

  private static long requiredPositiveSafeInteger(Map<String, Object> map, String field) {
    Object value = map.get(field);
    if (!(value instanceof Number number)) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      if (result <= 0L || result > JCS_MAX_SAFE_INTEGER) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      return result;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static void requireText(String value, int maxUtf8Bytes, String field) {
    if (value == null
        || value.isBlank()
        || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Malformed " + field);
    }
  }

  private static UUID requireUuid(UUID value, String field) {
    if (value == null
        || value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
      throw new IllegalArgumentException("Malformed " + field);
    }
    return value;
  }

  private static UUID parseUuid(Object value, String field) {
    if (!(value instanceof String text) || !CANONICAL_UUID.matcher(text).matches()) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static String requirePositiveLongDecimal(String value, String field) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException("Malformed " + field);
    }
    try {
      if (new BigInteger(value).compareTo(MAX_POSITIVE_BIGINT) > 0) {
        throw new IllegalArgumentException("Malformed " + field);
      }
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("Malformed " + field);
    }
    return value;
  }

  private static String parsePositiveLongDecimal(Object value, String field) {
    if (!(value instanceof String text)) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    try {
      return requirePositiveLongDecimal(text, field);
    } catch (IllegalArgumentException failure) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static Map<String, String> copyStringMap(Map<String, String> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException("Missing " + field);
    }
    Map<String, String> result = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          requireCanonicalUuidText(key, field + " key");
          result.put(key, requirePositiveLongDecimal(value, field));
        });
    return Collections.unmodifiableMap(result);
  }

  private static String requireCanonicalUuidText(String value, String field) {
    if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
      throw new IllegalArgumentException("Malformed " + field);
    }
    UUID parsed = UUID.fromString(value);
    if (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L) {
      throw new IllegalArgumentException("Malformed " + field);
    }
    return value;
  }

  private static boolean isCanonicalClientAddress(String value) {
    if (value == null || value.isEmpty() || value.length() > 64) {
      return false;
    }
    if (value.indexOf(':') >= 0) {
      try {
        byte[] address = InetAddress.getByName(value).getAddress();
        // InetAddress#getHostAddress emits the JDK's uncompressed IPv6 spelling. That spelling
        // is the canonical address representation for this envelope, including the full eight
        // groups; compressed IPv6 text is intentionally rejected.
        return address.length == 16
            && !isIpv4MappedIpv6(address)
            && InetAddress.getByAddress(address).getHostAddress().equals(value);
      } catch (Exception ignored) {
        return false;
      }
    }
    String[] octets = value.split("\\.", -1);
    if (octets.length != 4) {
      return false;
    }
    byte[] address = new byte[4];
    for (int index = 0; index < octets.length; index++) {
      String octet = octets[index];
      if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) {
        return false;
      }
      try {
        int parsed = Integer.parseInt(octet);
        if (parsed < 0 || parsed > 255 || !Integer.toString(parsed).equals(octet)) {
          return false;
        }
        address[index] = (byte) parsed;
      } catch (NumberFormatException failure) {
        return false;
      }
    }
    try {
      return InetAddress.getByAddress(address).getHostAddress().equals(value);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean isIpv4MappedIpv6(byte[] address) {
    if (address.length != 16) {
      return false;
    }
    for (int index = 0; index < 10; index++) {
      if (address[index] != 0) {
        return false;
      }
    }
    return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
  }

  private static Map<String, String> parseStringCounterMap(Object value, String field) {
    if (!(value instanceof Map<?, ?> source)) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      requireCanonicalUuidTextForRead(key);
      result.put(key, parsePositiveLongDecimal(entry.getValue(), field));
    }
    return Collections.unmodifiableMap(result);
  }

  private static void requireCanonicalUuidTextForRead(String value) {
    if (!CANONICAL_UUID.matcher(value).matches()) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
    } catch (IllegalArgumentException failure) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static Map<String, Object> requireObject(Map<String, Object> parent, String field) {
    Object value = parent.get(field);
    if (!(value instanceof Map<?, ?> source)) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static void exactFields(
      Map<String, Object> object, Set<String> required, Set<String> optional) {
    Set<String> allowed = new LinkedHashSet<>(required);
    allowed.addAll(optional);
    if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet())) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
  }

  private static void requireRsaKey(java.security.Key key) {
    int size =
        key instanceof RSAPublicKey publicKey
            ? publicKey.getModulus().bitLength()
            : key instanceof RSAPrivateKey privateKey ? privateKey.getModulus().bitLength() : 0;
    if (size < 2_048) {
      throw new IllegalArgumentException("Gateway context key must be RSA-2048 or stronger");
    }
  }

  private static VerificationException reject(FailureReason reason) {
    return new VerificationException(reason);
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** A fresh, complete source projection supplied only after the Gateway route validator. */
  public record GameplayConnectSource(
      UUID accountId,
      UUID tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID gameInstanceId,
      String pointerVersion,
      String catalogRevision,
      String connectScopeId,
      String requestId,
      AuthorityTuple authorityTuple,
      Map<String, String> membershipVersion,
      String connectTokenJti,
      String replayAdmissionFence,
      String playtestLifecycleId,
      String playtestStateGeneration,
      long sourceExpiresAtEpochSeconds) {
    public GameplayConnectSource {
      accountId = requireUuid(accountId, "accountId");
      tenantId = requireUuid(tenantId, "tenantId");
      realmId = requireUuid(realmId, "realmId");
      worldSlug = GameplayRoutingBundleValidator.requireCanonicalSlug(worldSlug, "worldSlug");
      realmSlug = GameplayRoutingBundleValidator.requireCanonicalSlug(realmSlug, "realmSlug");
      playableStateNamespaceId = requireUuid(playableStateNamespaceId, "playableStateNamespaceId");
      if (!"SHARED".equals(playableStateScope) && !"ISOLATED".equals(playableStateScope)) {
        throw new IllegalArgumentException("Malformed playableStateScope");
      }
      gameInstanceId = requireUuid(gameInstanceId, "gameInstanceId");
      pointerVersion = requirePositiveLongDecimal(pointerVersion, "pointerVersion");
      catalogRevision = requirePositiveLongDecimal(catalogRevision, "catalogRevision");
      requireText(connectScopeId, 2_048, "connectScopeId");
      requireText(requestId, 128, "requestId");
      Objects.requireNonNull(authorityTuple, "authorityTuple is required");
      membershipVersion = copyStringMap(membershipVersion, "membershipVersion");
      requireText(connectTokenJti, 128, "connectTokenJti");
      replayAdmissionFence =
          requirePositiveLongDecimal(replayAdmissionFence, "replayAdmissionFence");
      if (sourceExpiresAtEpochSeconds <= 0L) {
        throw new IllegalArgumentException("Malformed source expiry");
      }
      if (playtestLifecycleId == null) {
        if (playtestStateGeneration != null) {
          throw new IllegalArgumentException("Incomplete playtest context");
        }
      } else {
        playtestLifecycleId = requireCanonicalUuidText(playtestLifecycleId, "playtestLifecycleId");
        playtestStateGeneration =
            requirePositiveLongDecimal(playtestStateGeneration, "playtestStateGeneration");
      }
      authorityTuple.validateForTarget(
          accountId, tenantId, worldSlug, realmSlug, playtestLifecycleId, membershipVersion);
    }

    @Override
    public Map<String, String> membershipVersion() {
      return Map.copyOf(membershipVersion);
    }

    @Override
    public String toString() {
      return "GameplayConnectSource[redacted]";
    }
  }

  /** Gateway metadata projection; callers supply its trusted provenance and canonical address. */
  public record GatewayContextMetadata(
      String canonicalClientAddress,
      String gatewayRequestId,
      long issuedAtMillis,
      long verifiedAtMillis,
      long expiresAtMillis) {
    public GatewayContextMetadata {
      requireText(canonicalClientAddress, 64, "canonicalClientAddress");
      if (!isCanonicalClientAddress(canonicalClientAddress)) {
        throw new IllegalArgumentException("Malformed canonicalClientAddress");
      }
      if (gatewayRequestId == null || !SAFE_CORRELATION.matcher(gatewayRequestId).matches()) {
        throw new IllegalArgumentException("Malformed gatewayRequestId");
      }
      if (issuedAtMillis <= 0L || verifiedAtMillis <= 0L || expiresAtMillis <= 0L) {
        throw new IllegalArgumentException("Malformed context timestamps");
      }
    }

    @Override
    public String toString() {
      return "GatewayContextMetadata[redacted]";
    }
  }

  /** Exact copied Account authority evidence, validated in the fresh gameplay-connect profile. */
  public record AuthorityTuple(
      String issuerAuthGeneration,
      String accountAuthorityGeneration,
      Map<String, String> tenantAuthorityGeneration,
      Map<String, String> membershipAuthorityGeneration,
      List<PrivateRealmGrantVersion> privateRealmGrantVersions,
      AccountSecurityCutoff accountSecurityCutoff,
      Map<String, TenantBillingCutoff> tenantBillingCutoff) {
    public AuthorityTuple {
      issuerAuthGeneration =
          requirePositiveLongDecimal(issuerAuthGeneration, "issuerAuthGeneration");
      accountAuthorityGeneration =
          requirePositiveLongDecimal(accountAuthorityGeneration, "accountAuthorityGeneration");
      tenantAuthorityGeneration =
          copyStringMap(tenantAuthorityGeneration, "tenantAuthorityGeneration");
      membershipAuthorityGeneration =
          copyStringMap(membershipAuthorityGeneration, "membershipAuthorityGeneration");
      privateRealmGrantVersions = List.copyOf(Objects.requireNonNull(privateRealmGrantVersions));
      if (privateRealmGrantVersions.size() > 1) {
        throw new IllegalArgumentException("Too many private-realm grant entries");
      }
      if ("1".equals(accountAuthorityGeneration) != (accountSecurityCutoff == null)) {
        throw new IllegalArgumentException("Account cutoff presence does not match its generation");
      }
      tenantBillingCutoff = immutableMap(tenantBillingCutoff, "tenantBillingCutoff");
      if (tenantBillingCutoff.size() > 1) {
        throw new IllegalArgumentException("Too many tenant billing cutoffs");
      }
    }

    @Override
    public Map<String, String> tenantAuthorityGeneration() {
      return Map.copyOf(tenantAuthorityGeneration);
    }

    @Override
    public Map<String, String> membershipAuthorityGeneration() {
      return Map.copyOf(membershipAuthorityGeneration);
    }

    @Override
    public Map<String, TenantBillingCutoff> tenantBillingCutoff() {
      return Map.copyOf(tenantBillingCutoff);
    }

    @Override
    public String toString() {
      return "AuthorityTuple[redacted]";
    }

    private void validateForTarget(
        UUID accountId,
        UUID tenantId,
        String worldSlug,
        String realmSlug,
        String playtestLifecycleId,
        Map<String, String> membershipVersion) {
      String tenant = tenantId.toString();
      if (!tenantAuthorityGeneration.keySet().equals(Set.of(tenant))
          || !membershipAuthorityGeneration.keySet().equals(Set.of(tenant))
          || !membershipVersion.keySet().equals(Set.of(tenant))) {
        throw new IllegalArgumentException("Authority maps must name the exact selected tenant");
      }
      if (accountSecurityCutoff != null) {
        if (!accountSecurityCutoff.accountAuthorityGeneration().equals(accountAuthorityGeneration)
            || !accountSecurityCutoff.outboxStreamKey().equals(accountStream(accountId))) {
          throw new IllegalArgumentException("Account cutoff does not match the source authority");
        }
      }
      if (tenantBillingCutoff.size() == 1) {
        TenantBillingCutoff cutoff = tenantBillingCutoff.get(tenant);
        if (cutoff == null
            || !cutoff.tenantAuthorityGeneration().equals(tenantAuthorityGeneration.get(tenant))
            || !cutoff.outboxStreamKey().equals(tenantStream(tenantId))) {
          throw new IllegalArgumentException("Tenant cutoff does not match the source authority");
        }
      }
      if (playtestLifecycleId == null) {
        if (!privateRealmGrantVersions.isEmpty()) {
          throw new IllegalArgumentException("Public target cannot carry a private grant");
        }
      } else {
        if (privateRealmGrantVersions.size() != 1) {
          throw new IllegalArgumentException("Non-public target requires its exact grant");
        }
        PrivateRealmGrantVersion grant = privateRealmGrantVersions.get(0);
        if (!grant.tenantId().equals(tenant)
            || !grant.worldSlug().equals(worldSlug)
            || !grant.realmSlug().equals(realmSlug)
            || !grant.playtestLifecycleId().equals(playtestLifecycleId)) {
          throw new IllegalArgumentException("Private grant does not match the selected target");
        }
      }
    }

    private Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issuerAuthGeneration", issuerAuthGeneration);
      result.put("accountAuthorityGeneration", accountAuthorityGeneration);
      result.put("tenantAuthorityGeneration", tenantAuthorityGeneration);
      result.put("membershipAuthorityGeneration", membershipAuthorityGeneration);
      result.put(
          "privateRealmGrantVersions",
          privateRealmGrantVersions.stream().map(PrivateRealmGrantVersion::toMap).toList());
      if (accountSecurityCutoff != null) {
        result.put("accountSecurityCutoff", accountSecurityCutoff.toMap());
      }
      if (!tenantBillingCutoff.isEmpty()) {
        Map<String, Object> cutoffs = new LinkedHashMap<>();
        tenantBillingCutoff.forEach((key, cutoff) -> cutoffs.put(key, cutoff.toMap()));
        result.put("tenantBillingCutoff", cutoffs);
      }
      return Collections.unmodifiableMap(result);
    }

    private static AuthorityTuple fromMap(Map<String, Object> source, UUID tenantId) {
      exactFields(
          source,
          AUTHORITY_REQUIRED_FIELDS,
          Set.of("accountSecurityCutoff", "tenantBillingCutoff"));
      String issuerGeneration =
          parsePositiveLongDecimal(source.get("issuerAuthGeneration"), "issuerAuthGeneration");
      String accountGeneration =
          parsePositiveLongDecimal(
              source.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
      Map<String, String> tenantGenerations =
          parseStringCounterMap(
              source.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
      Map<String, String> membershipGenerations =
          parseStringCounterMap(
              source.get("membershipAuthorityGeneration"), "membershipAuthorityGeneration");
      Object grantsValue = source.get("privateRealmGrantVersions");
      if (!(grantsValue instanceof List<?> grants)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      List<PrivateRealmGrantVersion> parsedGrants = new ArrayList<>();
      for (Object value : grants) {
        if (!(value instanceof Map<?, ?> raw)) {
          throw reject(FailureReason.INVALID_SCHEMA);
        }
        Map<String, Object> grant = stringObject(raw);
        exactFields(grant, GRANT_FIELDS, Set.of());
        parsedGrants.add(
            new PrivateRealmGrantVersion(
                parseUuid(grant.get("tenantId"), "tenantId").toString(),
                requiredString(grant, "worldSlug", 120),
                requiredString(grant, "realmSlug", 120),
                parseUuid(grant.get("playtestLifecycleId"), "playtestLifecycleId").toString(),
                parsePositiveLongDecimal(grant.get("grantVersion"), "grantVersion")));
      }
      AccountSecurityCutoff accountCutoff = null;
      if (source.containsKey("accountSecurityCutoff")) {
        Map<String, Object> cutoff = stringObject(requireObject(source, "accountSecurityCutoff"));
        exactFields(cutoff, ACCOUNT_CUTOFF_FIELDS, Set.of());
        accountCutoff =
            new AccountSecurityCutoff(
                parsePositiveLongDecimal(
                    cutoff.get("accountAuthorityGeneration"), "account cutoff generation"),
                requiredString(cutoff, "outboxStreamKey", 2_048),
                parsePositiveLongDecimal(cutoff.get("outboxSequence"), "account cutoff sequence"));
      }
      Map<String, TenantBillingCutoff> billingCutoffs = Map.of();
      if (source.containsKey("tenantBillingCutoff")) {
        Map<String, Object> rawCutoffs = requireObject(source, "tenantBillingCutoff");
        if (rawCutoffs.size() != 1 || !rawCutoffs.containsKey(tenantId.toString())) {
          throw reject(FailureReason.INVALID_SCHEMA);
        }
        Map<String, Object> cutoff =
            stringObject(requireNestedObject(rawCutoffs.get(tenantId.toString())));
        exactFields(cutoff, TENANT_CUTOFF_FIELDS, Set.of());
        billingCutoffs =
            Map.of(
                tenantId.toString(),
                new TenantBillingCutoff(
                    parsePositiveLongDecimal(
                        cutoff.get("tenantAuthorityGeneration"), "billing cutoff generation"),
                    parsePositiveLongDecimal(
                        cutoff.get("tenantBillingSequence"), "billing cutoff sequence"),
                    requiredString(cutoff, "outboxStreamKey", 2_048),
                    parsePositiveLongDecimal(
                        cutoff.get("outboxSequence"), "billing cutoff sequence")));
      }
      try {
        return new AuthorityTuple(
            issuerGeneration,
            accountGeneration,
            tenantGenerations,
            membershipGenerations,
            parsedGrants,
            accountCutoff,
            billingCutoffs);
      } catch (IllegalArgumentException invalid) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
    }
  }

  public record PrivateRealmGrantVersion(
      String tenantId,
      String worldSlug,
      String realmSlug,
      String playtestLifecycleId,
      String grantVersion) {
    public PrivateRealmGrantVersion {
      tenantId = requireCanonicalUuidText(tenantId, "grant tenantId");
      worldSlug = GameplayRoutingBundleValidator.requireCanonicalSlug(worldSlug, "worldSlug");
      realmSlug = GameplayRoutingBundleValidator.requireCanonicalSlug(realmSlug, "realmSlug");
      playtestLifecycleId = requireCanonicalUuidText(playtestLifecycleId, "playtestLifecycleId");
      grantVersion = requirePositiveLongDecimal(grantVersion, "grantVersion");
    }

    @Override
    public String toString() {
      return "PrivateRealmGrantVersion[redacted]";
    }

    private Map<String, Object> toMap() {
      return Map.of(
          "tenantId", tenantId,
          "worldSlug", worldSlug,
          "realmSlug", realmSlug,
          "playtestLifecycleId", playtestLifecycleId,
          "grantVersion", grantVersion);
    }
  }

  public record AccountSecurityCutoff(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {
    public AccountSecurityCutoff {
      accountAuthorityGeneration =
          requirePositiveLongDecimal(accountAuthorityGeneration, "cutoff account generation");
      requireText(outboxStreamKey, 2_048, "cutoff outboxStreamKey");
      outboxSequence = requirePositiveLongDecimal(outboxSequence, "cutoff outboxSequence");
      if (outboxStreamKey.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Malformed cutoff outboxStreamKey");
      }
    }

    @Override
    public String toString() {
      return "AccountSecurityCutoff[redacted]";
    }

    private Map<String, Object> toMap() {
      return Map.of(
          "accountAuthorityGeneration", accountAuthorityGeneration,
          "outboxStreamKey", outboxStreamKey,
          "outboxSequence", outboxSequence);
    }
  }

  public record TenantBillingCutoff(
      String tenantAuthorityGeneration,
      String tenantBillingSequence,
      String outboxStreamKey,
      String outboxSequence) {
    public TenantBillingCutoff {
      tenantAuthorityGeneration =
          requirePositiveLongDecimal(tenantAuthorityGeneration, "billing tenant generation");
      tenantBillingSequence =
          requirePositiveLongDecimal(tenantBillingSequence, "tenantBillingSequence");
      requireText(outboxStreamKey, 2_048, "billing outboxStreamKey");
      outboxSequence = requirePositiveLongDecimal(outboxSequence, "billing outboxSequence");
      if (outboxStreamKey.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Malformed billing outboxStreamKey");
      }
    }

    @Override
    public String toString() {
      return "TenantBillingCutoff[redacted]";
    }

    private Map<String, Object> toMap() {
      return Map.of(
          "tenantAuthorityGeneration", tenantAuthorityGeneration,
          "tenantBillingSequence", tenantBillingSequence,
          "outboxStreamKey", outboxStreamKey,
          "outboxSequence", outboxSequence);
    }
  }

  public record ContextClaims(
      UUID accountId,
      UUID tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID gameInstanceId,
      String pointerVersion,
      String catalogRevision,
      String connectScopeId,
      String connectRequestId,
      AuthorityTuple authorityTuple,
      Map<String, String> membershipVersion,
      String connectTokenJti,
      String replayAdmissionFence,
      long issuedAtMillis,
      long verifiedAtMillis,
      long expiresAtMillis,
      String gatewayRequestId,
      String canonicalClientAddress,
      String playtestLifecycleId,
      String playtestStateGeneration) {
    public ContextClaims {
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(tenantId);
      Objects.requireNonNull(realmId);
      Objects.requireNonNull(playableStateNamespaceId);
      Objects.requireNonNull(gameInstanceId);
      Objects.requireNonNull(authorityTuple);
      membershipVersion = copyStringMap(membershipVersion, "membershipVersion");
      if (issuedAtMillis <= 0L || verifiedAtMillis <= 0L || expiresAtMillis <= 0L) {
        throw new IllegalArgumentException("Malformed context timestamps");
      }
    }

    @Override
    public Map<String, String> membershipVersion() {
      return Map.copyOf(membershipVersion);
    }

    @Override
    public String toString() {
      return "ContextClaims[redacted]";
    }

    private static ContextClaims from(
        GameplayConnectSource source, GatewayContextMetadata metadata) {
      return new ContextClaims(
          source.accountId(),
          source.tenantId(),
          source.realmId(),
          source.worldSlug(),
          source.realmSlug(),
          source.playableStateNamespaceId(),
          source.playableStateScope(),
          source.gameInstanceId(),
          source.pointerVersion(),
          source.catalogRevision(),
          source.connectScopeId(),
          source.requestId(),
          source.authorityTuple(),
          source.membershipVersion(),
          source.connectTokenJti(),
          source.replayAdmissionFence(),
          metadata.issuedAtMillis(),
          metadata.verifiedAtMillis(),
          metadata.expiresAtMillis(),
          metadata.gatewayRequestId(),
          metadata.canonicalClientAddress(),
          source.playtestLifecycleId(),
          source.playtestStateGeneration());
    }

    private Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("accountId", accountId.toString());
      result.put("tenantId", tenantId.toString());
      result.put("realmId", realmId.toString());
      result.put("worldSlug", worldSlug);
      result.put("realmSlug", realmSlug);
      result.put("playableStateNamespaceId", playableStateNamespaceId.toString());
      result.put("playableStateScope", playableStateScope);
      result.put("gameInstanceId", gameInstanceId.toString());
      result.put("pointerVersion", pointerVersion);
      result.put("catalogRevision", catalogRevision);
      result.put("connectScopeId", connectScopeId);
      result.put("connectRequestId", connectRequestId);
      result.put("audience", AUDIENCE);
      result.put("recipient", RECIPIENT);
      result.put("authorityTuple", authorityTuple.toMap());
      result.put("membershipVersion", membershipVersion);
      result.put("connectTokenJti", connectTokenJti);
      result.put("replayAdmissionFence", replayAdmissionFence);
      result.put("issuedAt", issuedAtMillis);
      result.put("verifiedAt", verifiedAtMillis);
      result.put("expiresAt", expiresAtMillis);
      result.put("gatewayRequestId", gatewayRequestId);
      result.put("canonicalClientAddress", canonicalClientAddress);
      result.put("transportClass", TRANSPORT_CLASS);
      if (playtestLifecycleId != null) {
        result.put("playtestLifecycleId", playtestLifecycleId);
        result.put("playtestStateGeneration", playtestStateGeneration);
      }
      return Collections.unmodifiableMap(result);
    }

    private static ContextClaims fromMap(Map<String, Object> value) {
      exactFields(
          value, CONTEXT_REQUIRED_FIELDS, Set.of("playtestLifecycleId", "playtestStateGeneration"));
      String audience = requiredString(value, "audience", 32);
      if (!AUDIENCE.equals(audience)) {
        throw reject(FailureReason.AUDIENCE_MISMATCH);
      }
      String recipient = requiredString(value, "recipient", 64);
      if (!RECIPIENT.equals(recipient)) {
        throw reject(FailureReason.RECIPIENT_MISMATCH);
      }
      String transportClass = requiredString(value, "transportClass", 32);
      if (!TRANSPORT_CLASS.equals(transportClass)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      UUID accountId = parseUuid(value.get("accountId"), "accountId");
      UUID tenantId = parseUuid(value.get("tenantId"), "tenantId");
      UUID realmId = parseUuid(value.get("realmId"), "realmId");
      String worldSlug = requiredString(value, "worldSlug", 120);
      String realmSlug = requiredString(value, "realmSlug", 120);
      UUID namespace = parseUuid(value.get("playableStateNamespaceId"), "playableStateNamespaceId");
      String stateScope = requiredString(value, "playableStateScope", 16);
      if (!"SHARED".equals(stateScope) && !"ISOLATED".equals(stateScope)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      UUID gameInstance = parseUuid(value.get("gameInstanceId"), "gameInstanceId");
      String pointerVersion =
          parsePositiveLongDecimal(value.get("pointerVersion"), "pointerVersion");
      String catalogRevision =
          parsePositiveLongDecimal(value.get("catalogRevision"), "catalogRevision");
      String connectScopeId = requiredString(value, "connectScopeId", 2_048);
      String requestId = requiredString(value, "connectRequestId", 128);
      Map<String, Object> rawTuple = requireObject(value, "authorityTuple");
      AuthorityTuple authorityTuple = AuthorityTuple.fromMap(rawTuple, tenantId);
      requireAuthorityTupleBound(authorityTuple.toMap());
      Map<String, String> membershipVersion =
          parseStringCounterMap(value.get("membershipVersion"), "membershipVersion");
      String jti = requiredString(value, "connectTokenJti", 128);
      String replayFence =
          parsePositiveLongDecimal(value.get("replayAdmissionFence"), "replayAdmissionFence");
      long issuedAt = requiredPositiveSafeInteger(value, "issuedAt");
      long verifiedAt = requiredPositiveSafeInteger(value, "verifiedAt");
      long expiresAt = requiredPositiveSafeInteger(value, "expiresAt");
      String gatewayRequestId = requiredString(value, "gatewayRequestId", 256);
      if (!SAFE_CORRELATION.matcher(gatewayRequestId).matches()) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      String address = requiredString(value, "canonicalClientAddress", 64);
      if (!isCanonicalClientAddress(address)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      String lifecycle = optionalString(value, "playtestLifecycleId", 36);
      String stateGeneration = optionalString(value, "playtestStateGeneration", 19);
      if ((lifecycle == null) != (stateGeneration == null)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      if (lifecycle != null) {
        lifecycle = parseUuid(lifecycle, "playtestLifecycleId").toString();
        stateGeneration = parsePositiveLongDecimal(stateGeneration, "playtestStateGeneration");
      }
      try {
        ContextClaims context =
            new ContextClaims(
                accountId,
                tenantId,
                realmId,
                GameplayRoutingBundleValidator.requireCanonicalSlug(worldSlug, "worldSlug"),
                GameplayRoutingBundleValidator.requireCanonicalSlug(realmSlug, "realmSlug"),
                namespace,
                stateScope,
                gameInstance,
                pointerVersion,
                catalogRevision,
                connectScopeId,
                requestId,
                authorityTuple,
                membershipVersion,
                jti,
                replayFence,
                issuedAt,
                verifiedAt,
                expiresAt,
                gatewayRequestId,
                address,
                lifecycle,
                stateGeneration);
        authorityTuple.validateForTarget(
            accountId, tenantId, worldSlug, realmSlug, lifecycle, membershipVersion);
        return context;
      } catch (IllegalArgumentException invalid) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
    }
  }

  public static final class VerifiedContext {
    private final String keyId;
    private final ContextClaims claims;

    private VerifiedContext(String keyId, ContextClaims claims) {
      if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
        throw new IllegalArgumentException("Malformed Gateway key id");
      }
      this.keyId = keyId;
      this.claims = Objects.requireNonNull(claims);
    }

    public String keyId() {
      return keyId;
    }

    public ContextClaims claims() {
      return claims;
    }
  }

  public static final class GatewaySigningKey {
    private final String keyId;
    private final PrivateKey privateKey;

    public GatewaySigningKey(String keyId, PrivateKey privateKey) {
      if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
        throw new IllegalArgumentException("Malformed Gateway key id");
      }
      this.privateKey =
          Objects.requireNonNull(privateKey, "Gateway private signing key is required");
      requireRsaKey(this.privateKey);
      this.keyId = keyId;
    }

    public String keyId() {
      return keyId;
    }
  }

  @FunctionalInterface
  public interface GatewayVerificationKeySource {
    /** Returns the dedicated Gateway public key, or {@code null} when the key id is unknown. */
    RSAPublicKey publicKeyFor(String keyId) throws KeySourceUnavailableException;
  }

  public static final class KeySourceUnavailableException extends RuntimeException {
    public KeySourceUnavailableException() {
      super("Gateway verification keys are unavailable");
    }
  }

  public enum FailureReason {
    MALFORMED_COMPACT,
    MALFORMED_JSON,
    INVALID_HEADER,
    NON_CANONICAL_ENCODING,
    UNKNOWN_KEY_ID,
    KEY_SOURCE_UNAVAILABLE,
    INVALID_SIGNATURE,
    INVALID_SCHEMA,
    INVALID_LIFETIME,
    AUDIENCE_MISMATCH,
    RECIPIENT_MISMATCH
  }

  public static final class VerificationException extends RuntimeException {
    private final FailureReason reason;

    private VerificationException(FailureReason reason) {
      super(
          "Gateway connect context rejected: " + reason.name().toLowerCase(java.util.Locale.ROOT));
      this.reason = reason;
    }

    public FailureReason reason() {
      return reason;
    }
  }

  private record CompactParts(
      byte[] header, byte[] payload, byte[] signature, String signingInput) {}

  private static String accountStream(UUID accountId) {
    return "account:auth-authority:v1:account/" + accountId;
  }

  private static String tenantStream(UUID tenantId) {
    return "account:auth-authority:v1:tenant/" + tenantId;
  }

  private static Map<String, TenantBillingCutoff> immutableMap(
      Map<String, TenantBillingCutoff> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException("Missing " + field);
    }
    Map<String, TenantBillingCutoff> result = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          requireCanonicalUuidText(key, field + " key");
          result.put(key, Objects.requireNonNull(value, field + " value"));
        });
    return Collections.unmodifiableMap(result);
  }

  private static Map<String, Object> requireNestedObject(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      throw reject(FailureReason.INVALID_SCHEMA);
    }
    return stringObject(source);
  }

  private static Map<String, Object> stringObject(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw reject(FailureReason.INVALID_SCHEMA);
      }
      result.put(key, entry.getValue());
    }
    return result;
  }
}
