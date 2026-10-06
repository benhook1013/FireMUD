package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountAsymmetricJwtVerifierTest {
  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final String KID = "signer-1";
  private static final AccountPublicJwksCache.SourceIdentity PIN =
      new AccountPublicJwksCache.SourceIdentity(
          "prod",
          "cluster-a",
          "11111111-1111-4111-8111-111111111111",
          "firemud-prod",
          "22222222-2222-4222-8222-222222222222",
          "33333333-3333-4333-8333-333333333333",
          "binding-7",
          "https://kubernetes.example:6443",
          "a".repeat(64));
  private static final Set<String> CLAIMS =
      Set.of(
          "iss",
          "sub",
          "jti",
          "accountId",
          "aud",
          "iat",
          "nbf",
          "exp",
          "tokenGeneration",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence",
          "scopedRoles");

  private final MutableClock clock = new MutableClock(NOW);
  private KeyPair keyPair;
  private AccountAsymmetricJwtVerifier verifier;
  private AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy policy;

  @BeforeEach
  void setUp() throws Exception {
    keyPair = rsa3072();
    String jwks = jwks(jwk(KID, (RSAPublicKey) keyPair.getPublic()));
    AccountPublicJwksCache cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    PIN, jwks.getBytes(StandardCharsets.UTF_8)),
            PIN,
            clock,
            Duration.ofSeconds(30));
    verifier = new AccountAsymmetricJwtVerifier(cache, clock);
    policy =
        new AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy(
            "credential-authenticate-account",
            ControlUiJwtProfileValidator.PROFILE,
            ControlUiJwtProfileValidator.TOKEN_TYPE,
            ControlUiJwtProfileValidator.ISSUER,
            ControlUiJwtProfileValidator.AUDIENCE,
            CLAIMS,
            Set.of(),
            1800L,
            5,
            16 * 1024,
            claims -> ControlUiJwtProfileValidator.validateClaims(claims, 8, 4096L));
  }

  @Test
  void verifiesRealRs256SignatureAndReturnsOnlyImmutableRouteBoundClaims() throws Exception {
    String token =
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            KID,
            "JWT",
            keyPair);

    AccountAsymmetricJwtVerifier.VerifiedClaims verified = verifier.verify(token, policy);

    assertThat(verified.routeId()).isEqualTo("credential-authenticate-account");
    assertThat(verified.profile()).isEqualTo(ControlUiJwtProfileValidator.PROFILE);
    assertThat(verified.tokenType()).isEqualTo(ControlUiJwtProfileValidator.TOKEN_TYPE);
    assertThat(verified.keyId()).isEqualTo(KID);
    assertThat(verified.claims().get("iss")).isEqualTo(ControlUiJwtProfileValidator.ISSUER);
    assertThat(verified.claims()).doesNotContainKeys("tokenType", "tokenProfile");
    assertThat(verified.getClass().getDeclaredFields())
        .extracting(java.lang.reflect.Field::getName)
        .containsExactlyInAnyOrder("routeId", "profile", "tokenType", "keyId", "claims");
    assertThatThrownBy(() -> verified.claims().put("extra", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> tuple = (Map<String, Object>) verified.claims().get("authorityTuple");
    assertThat(tuple.get("issuerAuthGeneration")).isEqualTo("1");
    assertThat(tuple.get("accountAuthorityGeneration")).isEqualTo("1");
    assertThat(tuple.get("tenantAuthorityGeneration")).isEqualTo(Map.of());
    assertThat(tuple.get("membershipAuthorityGeneration")).isEqualTo(Map.of());
    assertThat(tuple.get("privateRealmGrantVersions")).isEqualTo(java.util.List.of());
    assertThat(verified.claims().get("tokenGeneration")).isEqualTo("1");
    assertThat(verified.claims().get("issuanceFence")).isEqualTo("1");
    for (String field : java.util.List.of("iat", "nbf", "exp")) {
      assertThat(verified.claims().get(field)).isInstanceOf(Number.class);
    }
    assertThatThrownBy(() -> tuple.put("tenant", "x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void exactOversizedCountersSurviveSignatureVerificationAndFractionalCountersDeny()
      throws Exception {
    java.math.BigInteger value = java.math.BigInteger.TEN.pow(100).add(java.math.BigInteger.ONE);
    Map<String, Object> claims =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120L);
    claims.put("tokenGeneration", value.toString());
    claims.put("issuanceFence", value.add(java.math.BigInteger.ONE).toString());
    String compact = token(claims, "RS256", KID, "JWT", keyPair);
    var verified = verifier.verify(compact, policy);
    assertThat(verified.claims().get("tokenGeneration")).isEqualTo(value.toString());
    assertThat(verified.claims().get("issuanceFence"))
        .isEqualTo(value.add(java.math.BigInteger.ONE).toString());
    claims.put("tokenGeneration", new java.math.BigDecimal("1.000000000000000000001"));
    assertDenied(token(claims, "RS256", KID, "JWT", keyPair));
    claims.put("tokenGeneration", 0L);
    assertDenied(token(claims, "RS256", KID, "JWT", keyPair));
    claims.put("tokenGeneration", -1L);
    assertDenied(token(claims, "RS256", KID, "JWT", keyPair));
    claims.put("tokenGeneration", 1L);
    assertDenied(token(claims, "RS256", KID, "JWT", keyPair));
  }

  @Test
  void verifierChecksCanonicalLineageAndFenceBeforeTheProfileCallback() throws Exception {
    AtomicInteger profileCalls = new AtomicInteger();
    // Isolate the verifier-owned wire checks: a profile validator must not mask a missed reader.
    var counterPolicy =
        new AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy(
            policy.routeId(),
            policy.profile(),
            policy.tokenType(),
            policy.issuer(),
            policy.audience(),
            policy.requiredClaims(),
            policy.optionalClaims(),
            policy.maximumLifetimeSeconds(),
            policy.allowedClockSkewSeconds(),
            policy.maxCompactTokenBytes(),
            claims -> profileCalls.incrementAndGet());
    for (String value :
        java.util.List.of(
            "1",
            "9007199254740992",
            "9007199254740993",
            "9223372036854775808",
            "9223372036854775809")) {
      Map<String, Object> claims =
          validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120L);
      claims.put("tokenGeneration", value);
      claims.put("issuanceFence", value);
      var verified = verifier.verify(token(claims, "RS256", KID, "JWT", keyPair), counterPolicy);
      assertThat(verified.claims().get("tokenGeneration")).isEqualTo(value);
      assertThat(verified.claims().get("issuanceFence")).isEqualTo(value);
      for (String field : java.util.List.of("iat", "nbf", "exp")) {
        assertThat(verified.claims().get(field)).isInstanceOf(Number.class);
      }
    }
    assertThat(profileCalls).hasValue(5);
    for (String field : java.util.List.of("tokenGeneration", "issuanceFence")) {
      for (Object invalid :
          java.util.List.of(
              1L,
              1.0d,
              new java.math.BigDecimal("1.5"),
              "0",
              "-1",
              "+1",
              "01",
              " 1",
              "1 ",
              "1.0",
              "1e3",
              "",
              "bad")) {
        Map<String, Object> claims =
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120L);
        claims.put(field, invalid);
        String signed = token(claims, "RS256", KID, "JWT", keyPair);
        assertThatThrownBy(() -> verifier.verify(signed, counterPolicy))
            .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
            .hasNoCause();
      }
    }
    assertThat(profileCalls).hasValue(5);
  }

  @Test
  void wrongIssuerAudienceHeaderTypeAlgorithmAndTimeAreDenied() throws Exception {
    Map<String, Object> wrongIssuer =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    wrongIssuer.put("iss", "other-issuer");
    assertDenied(token(wrongIssuer, "RS256", KID, "JWT", keyPair));

    Map<String, Object> wrongAudience =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    wrongAudience.put("aud", "other-audience");
    assertDenied(token(wrongAudience, "RS256", KID, "JWT", keyPair));

    assertDenied(
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "HS256",
            KID,
            "JWT",
            keyPair));
    assertDenied(
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            KID,
            "private_player_delegation",
            keyPair));
    assertDenied(
        token(
            validClaims(
                NOW.getEpochSecond() - 400, NOW.getEpochSecond() - 400, NOW.getEpochSecond() - 1),
            "RS256",
            KID,
            "JWT",
            keyPair));
    assertDenied(
        token(
            validClaims(
                NOW.getEpochSecond() + 20, NOW.getEpochSecond() + 20, NOW.getEpochSecond() + 100),
            "RS256",
            KID,
            "JWT",
            keyPair));
  }

  @Test
  void rejectsDuplicateTrailingExtraClaimsAndMismatchedSignatureKey() throws Exception {
    String validPayload =
        claimsJson(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120));
    assertDenied(
        rawToken(
            "{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"typ\":\"JWT\"}",
            validPayload.replaceFirst("\\{", "{\"iss\":\"duplicate\","),
            keyPair));
    assertDenied(
        rawToken(
            "{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"typ\":\"JWT\"}",
            validPayload + " {}",
            keyPair));
    Map<String, Object> extra =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    extra.put("unrecognized", true);
    assertDenied(token(extra, "RS256", KID, "JWT", keyPair));

    KeyPair other = rsa3072();
    assertDenied(
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            KID,
            "JWT",
            other));
  }

  @Test
  void cacheUnavailableAfterMaximumAgeDoesNotPermitStaleSignatureUse() throws Exception {
    String jwks = jwks(jwk(KID, (RSAPublicKey) keyPair.getPublic()));
    boolean[] unavailable = {false};
    AccountPublicJwksCache cache =
        new AccountPublicJwksCache(
            () -> {
              if (unavailable[0]) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return new AccountPublicJwksCache.PublicJwksSnapshot(
                  PIN, jwks.getBytes(StandardCharsets.UTF_8));
            },
            PIN,
            clock,
            Duration.ofSeconds(5));
    AccountAsymmetricJwtVerifier staleVerifier = new AccountAsymmetricJwtVerifier(cache, clock);
    String valid =
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            KID,
            "JWT",
            keyPair);
    assertThat(staleVerifier.verify(valid, policy).claims().get("jti"))
        .isEqualTo("22222222-2222-4222-8222-222222222222");
    unavailable[0] = true;
    clock.advance(Duration.ofSeconds(6));
    assertThatThrownBy(() -> staleVerifier.verify(valid, policy))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationUnavailableException.class)
        .hasNoCause();
    assertThatThrownBy(() -> staleVerifier.verify(valid, policy))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void unknownKidForcesOneSnapshotRefreshAndThenVerifiesAgainstTheNewPublicKey() throws Exception {
    KeyPair rotated = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<String> currentJwks =
        new AtomicReference<>(jwks(jwk(KID, (RSAPublicKey) keyPair.getPublic())));
    AccountPublicJwksCache cache =
        new AccountPublicJwksCache(
            () -> {
              loads.incrementAndGet();
              return new AccountPublicJwksCache.PublicJwksSnapshot(
                  PIN, currentJwks.get().getBytes(StandardCharsets.UTF_8));
            },
            PIN,
            clock,
            Duration.ofSeconds(30));
    AccountAsymmetricJwtVerifier rotationVerifier = new AccountAsymmetricJwtVerifier(cache, clock);
    String oldToken =
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            KID,
            "JWT",
            keyPair);
    rotationVerifier.verify(oldToken, policy);
    currentJwks.set(
        jwks(
            jwk(KID, (RSAPublicKey) keyPair.getPublic())
                + ","
                + jwk("signer-2", (RSAPublicKey) rotated.getPublic())));

    String rotatedToken =
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            "signer-2",
            "JWT",
            rotated);
    assertThat(rotationVerifier.verify(rotatedToken, policy).claims().get("iss"))
        .isEqualTo(ControlUiJwtProfileValidator.ISSUER);
    assertThat(rotationVerifier.verify(rotatedToken, policy).claims().get("iss"))
        .isEqualTo(ControlUiJwtProfileValidator.ISSUER);
    assertThat(loads).hasValue(2);
  }

  @Test
  void claimShapeValidationRunsOnlyAfterSuccessfulSignatureVerification() throws Exception {
    AtomicInteger shapeValidations = new AtomicInteger();
    AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy observingPolicy =
        new AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy(
            "credential-authenticate-account",
            ControlUiJwtProfileValidator.PROFILE,
            ControlUiJwtProfileValidator.TOKEN_TYPE,
            ControlUiJwtProfileValidator.ISSUER,
            ControlUiJwtProfileValidator.AUDIENCE,
            CLAIMS,
            Set.of(),
            1800L,
            5,
            16 * 1024,
            claims -> {
              shapeValidations.incrementAndGet();
              ControlUiJwtProfileValidator.validateClaims(claims, 8, 4096L);
            });
    String valid =
        token(
            validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120),
            "RS256",
            KID,
            "JWT",
            keyPair);

    verifier.verify(valid, observingPolicy);
    assertThat(shapeValidations).hasValue(1);

    String invalidSignature = corruptSignature(valid);
    assertThatThrownBy(() -> verifier.verify(invalidSignature, observingPolicy))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasNoCause();
    assertThat(shapeValidations).hasValue(1);
  }

  private void assertDenied(String token) {
    assertDenied(verifier, token);
  }

  private static void assertDenied(AccountAsymmetricJwtVerifier verifier, String token) {
    assertThatThrownBy(() -> verifier.verify(token, policyForTest()))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class)
        .hasMessage("Account JWT verification failed")
        .hasNoCause();
  }

  private static AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy policyForTest() {
    return new AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy(
        "credential-authenticate-account",
        ControlUiJwtProfileValidator.PROFILE,
        ControlUiJwtProfileValidator.TOKEN_TYPE,
        ControlUiJwtProfileValidator.ISSUER,
        ControlUiJwtProfileValidator.AUDIENCE,
        CLAIMS,
        Set.of(),
        300,
        5,
        16 * 1024,
        claims -> ControlUiJwtProfileValidator.validateClaims(claims, 8, 4096L));
  }

  private static Map<String, Object> validClaims(long issuedAt, long notBefore, long expiresAt) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ControlUiJwtProfileValidator.ISSUER);
    claims.put("sub", "11111111-1111-4111-8111-111111111111");
    claims.put("jti", "22222222-2222-4222-8222-222222222222");
    claims.put("accountId", "11111111-1111-4111-8111-111111111111");
    claims.put("aud", ControlUiJwtProfileValidator.AUDIENCE);
    claims.put("iat", issuedAt);
    claims.put("nbf", notBefore);
    claims.put("exp", expiresAt);
    claims.put("tokenGeneration", "1");
    claims.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(),
            "membershipAuthorityGeneration", Map.of(),
            "privateRealmGrantVersions", java.util.List.of()));
    claims.put("membershipVersion", Map.of());
    claims.put("issuanceFence", "1");
    claims.put("scopedRoles", Map.of());
    return claims;
  }

  private static String token(
      Map<String, Object> claims, String algorithm, String kid, String typ, KeyPair signer)
      throws Exception {
    String header =
        "{\"alg\":\"" + algorithm + "\",\"kid\":\"" + kid + "\",\"typ\":\"" + typ + "\"}";
    return rawToken(header, claimsJson(claims), signer);
  }

  private static String rawToken(String header, String payload, KeyPair signer) throws Exception {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String headerSegment = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8));
    String payloadSegment = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    byte[] signingInput =
        (headerSegment + "." + payloadSegment).getBytes(StandardCharsets.US_ASCII);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(signer.getPrivate());
    signature.update(signingInput);
    return headerSegment + "." + payloadSegment + "." + encoder.encodeToString(signature.sign());
  }

  private static String corruptSignature(String token) {
    int separator = token.lastIndexOf('.');
    char replacement = token.charAt(separator + 1) == 'A' ? 'B' : 'A';
    return token.substring(0, separator + 1) + replacement + token.substring(separator + 2);
  }

  private static String claimsJson(Map<String, Object> claims) {
    // Build the canonical profile's bounded nested object explicitly so test tokens exercise the
    // same normal JSON shapes without relying on an unrelated mapper configuration.
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : claims.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(entry.getKey()).append("\":");
      Object value = entry.getValue();
      if (value instanceof String text) {
        json.append('"').append(text).append('"');
      } else if (value instanceof Number || value instanceof Boolean) {
        json.append(value);
      } else if (("membershipVersion".equals(entry.getKey())
          || "scopedRoles".equals(entry.getKey()))) {
        json.append("{}");
      } else if ("authorityTuple".equals(entry.getKey())) {
        json.append(
            "{\"issuerAuthGeneration\":\"1\",\"accountAuthorityGeneration\":\"1\",\"tenantAuthorityGeneration\":{},\"membershipAuthorityGeneration\":{},\"privateRealmGrantVersions\":[]}");
      } else {
        throw new IllegalArgumentException("Unexpected test claim");
      }
    }
    json.append('}');
    return json.toString();
  }

  private static String jwks(String key) {
    return "{\"keys\":[" + key + "]}";
  }

  private static String jwk(String kid, RSAPublicKey key) {
    byte[] modulus = unsigned(key.getModulus().toByteArray());
    byte[] exponent = unsigned(key.getPublicExponent().toByteArray());
    return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
        + kid
        + "\",\"n\":\""
        + Base64.getUrlEncoder().withoutPadding().encodeToString(modulus)
        + "\",\"e\":\""
        + Base64.getUrlEncoder().withoutPadding().encodeToString(exponent)
        + "\"}";
  }

  private static byte[] unsigned(byte[] value) {
    return value.length > 1 && value[0] == 0
        ? java.util.Arrays.copyOfRange(value, 1, value.length)
        : value;
  }

  private static KeyPair rsa3072() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    return generator.generateKeyPair();
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
