package net.firedevops.firemud.accountservice.security;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.HistoricalGatewayConnectEvidence;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies an Account-signed selected-target gameplay-connect JWT source.
 *
 * <p>This original-source component verifies only against the Account public keys explicitly
 * supplied at construction. It does not read or publish keys, issue tokens, consult current
 * authority, or authorize gameplay admission.
 */
public final class AccountGameplayConnectSourceVerifier {
  private static final String INVALID_TOKEN_MESSAGE = "invalid Account gameplay-connect source JWT";
  private static final String SAFE_PROJECTION_CORRELATION_ID =
      "account-gameplay-connect-source-verifier";
  private static final long MAX_FUTURE_ISSUED_AT_SECONDS = 5L;
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final JsonMapper JSON =
      JsonMapper.builder(JSON_FACTORY)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private final String exactIssuer;
  private final Map<String, PublicKey> registeredKeys;
  private final int maxCompactJwtBytes;
  private final Clock clock;

  /**
   * Creates an isolated source verifier with the exact Account issuer and public-key registry.
   *
   * @param exactIssuer exact Account {@code iss} value accepted by this verifier
   * @param registeredKeys Account-registered public keys indexed by their exact JOSE {@code kid}
   * @param maxCompactJwtBytes maximum UTF-8 byte length of an accepted compact JWT
   * @param clock clock used for current source-token time checks
   */
  public AccountGameplayConnectSourceVerifier(
      String exactIssuer,
      Map<String, PublicKey> registeredKeys,
      int maxCompactJwtBytes,
      Clock clock) {
    if (exactIssuer == null || exactIssuer.isBlank()) {
      throw new IllegalArgumentException("exact Account issuer is required");
    }
    if (registeredKeys == null || registeredKeys.isEmpty()) {
      throw new IllegalArgumentException("Account verification keys are required");
    }
    if (maxCompactJwtBytes <= 0) {
      throw new IllegalArgumentException("maximum compact JWT size must be positive");
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock is required");
    }

    Map<String, PublicKey> copiedKeys = new LinkedHashMap<>();
    for (Map.Entry<String, PublicKey> entry : registeredKeys.entrySet()) {
      String kid = entry.getKey();
      PublicKey key = entry.getValue();
      if (kid == null || kid.isBlank() || !isSupportedKey(key)) {
        throw new IllegalArgumentException("Account verification key registry is invalid");
      }
      copiedKeys.put(kid, key);
    }
    this.exactIssuer = exactIssuer;
    this.registeredKeys = Collections.unmodifiableMap(copiedKeys);
    this.maxCompactJwtBytes = maxCompactJwtBytes;
    this.clock = clock;
  }

  /**
   * Verifies a compact Account gameplay-connect source JWT and returns its closed source claims.
   *
   * <p>Every verification failure has the same sanitized exception message. Neither the source
   * token, credential material, nor detailed parser or cryptographic failures are retained.
   *
   * @param compactJwt compact Account-signed JWT
   * @return deeply immutable exact source-claim map; the compact JWT is never included
   * @throws IllegalArgumentException if the token is malformed, untrusted, expired, or outside the
   *     registered source profile
   */
  public Map<String, Object> verify(String compactJwt) {
    try {
      SignedCompactSource source = verifySignedCompactSource(compactJwt);
      Instant now = clock.instant();
      validateIssuerAudienceAndTimeAt(source.claims(), now);
      GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
          source.claims(), now.getEpochSecond(), SAFE_PROJECTION_CORRELATION_ID);
      return deepImmutableObject(source.claims());
    } catch (RuntimeException | GeneralSecurityException ex) {
      throw invalidToken();
    }
  }

  /**
   * Verifies an Account gameplay-connect source only as historical evidence for an already
   * signature-verified Gateway assertion. The Gateway assertion's signed {@code verifiedAt} is the
   * source-time anchor; this method does not change or backdate this verifier's clock.
   *
   * <p>The returned value is deliberately distinct from the current source claim map. It proves the
   * Account signature and closed source profile, plus exact correspondence to the supplied
   * historical Gateway projection. It does not prove that Gateway accepted the assertion, that the
   * source was durably issued, or that any current authority, registry, recovery, or admission
   * condition holds. The compact Account JWT is not retained.
   *
   * <p>Every verification failure has the same sanitized exception message. Neither the source
   * token nor detailed parser or cryptographic failures are retained.
   *
   * @param compactJwt compact Account-signed JWT
   * @param historicalGatewayEvidence Gateway evidence produced by the historical Gateway codec
   * @return immutable Account source claims and their historical Gateway correspondence
   * @throws IllegalArgumentException if either assertion is malformed, untrusted, or mismatched
   */
  public HistoricalAccountGameplayConnectEvidence verifyHistorical(
      String compactJwt, HistoricalGatewayConnectEvidence historicalGatewayEvidence) {
    try {
      if (historicalGatewayEvidence == null) {
        throw invalidToken();
      }
      SignedCompactSource source = verifySignedCompactSource(compactJwt);
      BigInteger gatewayVerifiedAt = historicalGatewayEvidence.integer("verifiedAt");
      if (gatewayVerifiedAt == null || gatewayVerifiedAt.signum() <= 0) {
        throw invalidToken();
      }
      Instant originalGatewayVerificationTime =
          Instant.ofEpochSecond(gatewayVerifiedAt.longValueExact());
      validateIssuerAudienceAndTimeAt(source.claims(), originalGatewayVerificationTime);
      GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesHistoricalGatewayEvidence(
          source.claims(), historicalGatewayEvidence);
      return new HistoricalAccountGameplayConnectEvidence(
          source.keyId(), deepImmutableObject(source.claims()), historicalGatewayEvidence);
    } catch (RuntimeException | GeneralSecurityException ex) {
      throw invalidToken();
    }
  }

  private SignedCompactSource verifySignedCompactSource(String compactJwt)
      throws GeneralSecurityException {
    String[] segments = splitCanonicalCompactJwt(compactJwt);
    byte[] protectedHeaderBytes = decodeCanonicalBase64Url(segments[0]);
    byte[] payloadBytes = decodeCanonicalBase64Url(segments[1]);
    byte[] signatureBytes = decodeCanonicalBase64Url(segments[2]);

    Map<String, Object> header = parseJsonObject(protectedHeaderBytes);
    validateProtectedHeaderShape(header);
    String algorithm = requireText(header, "alg");
    String kid = requireText(header, "kid");
    if (header.containsKey("typ") && !"JWT".equals(header.get("typ"))) {
      throw invalidToken();
    }

    PublicKey key = registeredKeys.get(kid);
    if (key == null) {
      throw invalidToken();
    }
    String jcaAlgorithm = requireCompatibleAlgorithm(algorithm, key);
    verifySignature(segments[0], segments[1], signatureBytes, key, jcaAlgorithm);

    Map<String, Object> sourceClaims = parseJsonObject(payloadBytes);
    return new SignedCompactSource(kid, sourceClaims);
  }

  private String[] splitCanonicalCompactJwt(String compactJwt) {
    if (compactJwt == null || compactJwt.isEmpty() || compactJwt.length() > maxCompactJwtBytes) {
      throw invalidToken();
    }
    for (int index = 0; index < compactJwt.length(); index++) {
      if (compactJwt.charAt(index) > 0x7f) {
        throw invalidToken();
      }
    }

    int firstDot = compactJwt.indexOf('.');
    int secondDot = firstDot < 0 ? -1 : compactJwt.indexOf('.', firstDot + 1);
    if (firstDot <= 0
        || secondDot <= firstDot + 1
        || secondDot == compactJwt.length() - 1
        || compactJwt.indexOf('.', secondDot + 1) >= 0) {
      throw invalidToken();
    }
    return new String[] {
      compactJwt.substring(0, firstDot),
      compactJwt.substring(firstDot + 1, secondDot),
      compactJwt.substring(secondDot + 1)
    };
  }

  private byte[] decodeCanonicalBase64Url(String segment) {
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(segment);
      String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
      if (!canonical.equals(segment)) {
        throw invalidToken();
      }
      return decoded;
    } catch (IllegalArgumentException ex) {
      throw invalidToken();
    }
  }

  private Map<String, Object> parseJsonObject(byte[] jsonBytes) {
    try (var parser = JSON_FACTORY.createParser(jsonBytes)) {
      Object decoded = JSON.readValue(parser, Object.class);
      if (parser.nextToken() != null || !(decoded instanceof Map<?, ?> rawObject)) {
        throw invalidToken();
      }
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawObject.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalidToken();
        }
        result.put(key, entry.getValue());
      }
      return result;
    } catch (RuntimeException ex) {
      throw invalidToken();
    }
  }

  private void validateProtectedHeaderShape(Map<String, Object> header) {
    if (!HEADER_FIELDS.containsAll(header.keySet())
        || !header.containsKey("alg")
        || !header.containsKey("kid")) {
      throw invalidToken();
    }
  }

  private String requireCompatibleAlgorithm(String algorithm, PublicKey key) {
    if (key instanceof RSAPublicKey) {
      return switch (algorithm) {
        case "RS256" -> "SHA256withRSA";
        case "RS384" -> "SHA384withRSA";
        case "RS512" -> "SHA512withRSA";
        default -> throw invalidToken();
      };
    }
    if (key instanceof EdECPublicKey edKey
        && "Ed25519".equals(edKey.getParams().getName())
        && "EdDSA".equals(algorithm)) {
      return "Ed25519";
    }
    throw invalidToken();
  }

  private void verifySignature(
      String protectedSegment,
      String payloadSegment,
      byte[] signatureBytes,
      PublicKey key,
      String jcaAlgorithm)
      throws GeneralSecurityException {
    if (signatureBytes.length == 0) {
      throw invalidToken();
    }
    if ("Ed25519".equals(jcaAlgorithm) && signatureBytes.length != 64) {
      throw invalidToken();
    }
    byte[] signingInput =
        (protectedSegment + "." + payloadSegment).getBytes(StandardCharsets.US_ASCII);
    Signature verifier = Signature.getInstance(jcaAlgorithm);
    verifier.initVerify(key);
    verifier.update(signingInput);
    if (!verifier.verify(signatureBytes)) {
      throw invalidToken();
    }
  }

  private void validateIssuerAudienceAndTimeAt(Map<String, Object> claims, Instant validationTime) {
    if (!exactIssuer.equals(claims.get("iss"))
        || !(claims.get("iss") instanceof String)
        || !"gameplay-connect".equals(claims.get("aud"))) {
      throw invalidToken();
    }
    BigInteger issuedAt = requireInteger(claims, "iat");
    BigInteger expiresAt = requireInteger(claims, "exp");
    if (issuedAt.signum() <= 0 || expiresAt.compareTo(issuedAt) <= 0) {
      throw invalidToken();
    }
    try {
      Instant issuedAtInstant = Instant.ofEpochSecond(issuedAt.longValueExact());
      Instant expiresAtInstant = Instant.ofEpochSecond(expiresAt.longValueExact());
      if (!validationTime.isBefore(expiresAtInstant)
          || issuedAt
                  .subtract(BigInteger.valueOf(validationTime.getEpochSecond()))
                  .compareTo(BigInteger.valueOf(MAX_FUTURE_ISSUED_AT_SECONDS))
              > 0) {
        throw invalidToken();
      }
      // Force validation of both epoch-second claims before the shared profile codec sees them.
      if (issuedAtInstant.isAfter(expiresAtInstant)) {
        throw invalidToken();
      }
    } catch (ArithmeticException | DateTimeException ex) {
      throw invalidToken();
    }
  }

  private BigInteger requireInteger(Map<String, Object> values, String field) {
    Object value = values.get(field);
    if (!(value instanceof BigInteger integer)) {
      throw invalidToken();
    }
    return integer;
  }

  private String requireText(Map<String, Object> values, String field) {
    Object value = values.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw invalidToken();
    }
    return text;
  }

  private Map<String, Object> deepImmutableObject(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, deepImmutableValue(value)));
    return Collections.unmodifiableMap(copy);
  }

  private Object deepImmutableValue(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Map<?, ?> sourceMap) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalidToken();
        }
        copy.put(key, deepImmutableValue(entry.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> sourceList) {
      List<Object> copy = new ArrayList<>(sourceList.size());
      for (Object entry : sourceList) {
        copy.add(deepImmutableValue(entry));
      }
      return Collections.unmodifiableList(copy);
    }
    throw invalidToken();
  }

  private static boolean isSupportedKey(PublicKey key) {
    return (key instanceof RSAPublicKey rsaKey && rsaKey.getModulus().bitLength() >= 2048)
        || (key instanceof EdECPublicKey edKey && "Ed25519".equals(edKey.getParams().getName()));
  }

  private static IllegalArgumentException invalidToken() {
    return new IllegalArgumentException(INVALID_TOKEN_MESSAGE);
  }

  private record SignedCompactSource(String keyId, Map<String, Object> claims) {}
}
