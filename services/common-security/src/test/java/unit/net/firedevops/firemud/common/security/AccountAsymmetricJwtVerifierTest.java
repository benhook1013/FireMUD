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
import java.util.List;
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
          "issuanceFence");

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
            GameSessionAccountDelegationProfile.PROFILE,
            GameSessionAccountDelegationProfile.TYPE,
            GameSessionAccountDelegationProfile.ISSUER,
            GameSessionAccountDelegationProfile.AUDIENCE,
            CLAIMS,
            Set.of(),
            GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
            5,
            GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
            GameSessionAccountDelegationJwtProfileValidator::validateClaims);
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
    assertThat(verified.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertThat(verified.tokenType()).isEqualTo(GameSessionAccountDelegationProfile.TYPE);
    assertThat(verified.keyId()).isEqualTo(KID);
    assertThat(verified.claims().get("iss")).isEqualTo(GameSessionAccountDelegationProfile.ISSUER);
    assertThat(verified.claims()).doesNotContainKeys("tokenType", "tokenProfile");
    assertThat(verified.getClass().getDeclaredFields())
        .extracting(java.lang.reflect.Field::getName)
        .containsExactlyInAnyOrder("routeId", "profile", "tokenType", "keyId", "claims");
    assertThatThrownBy(() -> verified.claims().put("extra", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> tuple = (Map<String, Object>) verified.claims().get("authorityTuple");
    assertThatThrownBy(() -> tuple.put("tenant", "x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(verified.claims().get("iat")).isInstanceOf(Number.class);
    assertThat(verified.claims().get("tokenGeneration")).isEqualTo("1");
    assertThat(verified.claims().get("issuanceFence")).isEqualTo("1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifiedClaimsDeepCopyAndFreezeCallerOwnedNestedValues() {
    Map<String, Object> nested = new LinkedHashMap<>();
    List<Object> nestedList = new java.util.ArrayList<>();
    Map<String, Object> listEntry = new LinkedHashMap<>();
    listEntry.put("value", "before");
    nestedList.add(listEntry);
    nested.put("items", nestedList);
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("nested", nested);

    AccountAsymmetricJwtVerifier.VerifiedClaims verified =
        new AccountAsymmetricJwtVerifier.VerifiedClaims("route", "profile", "type", "kid", source);

    listEntry.put("value", "after");
    nestedList.add("after");
    nested.put("added", true);
    source.put("added", true);

    Map<?, ?> copiedNested = (Map<?, ?>) verified.claims().get("nested");
    List<?> copiedList = (List<?>) copiedNested.get("items");
    Map<?, ?> copiedListEntry = (Map<?, ?>) copiedList.get(0);
    assertThat(copiedListEntry.get("value")).isEqualTo("before");
    assertThat(copiedList).hasSize(1);
    assertThat(copiedNested.containsKey("added")).isFalse();
    assertThat(verified.claims()).doesNotContainKey("added");
    assertThatThrownBy(() -> ((Map<Object, Object>) copiedNested).put("added", true))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<Object>) copiedList).add("value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((Map<Object, Object>) copiedListEntry).put("value", "after"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNumericDelegationCountersWithoutChangingNumericJwtTimes() throws Exception {
    Map<String, Object> numericTokenGeneration =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    numericTokenGeneration.put("tokenGeneration", 1L);
    assertDenied(token(numericTokenGeneration, "RS256", KID, "JWT", keyPair));

    Map<String, Object> numericFence =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    numericFence.put("issuanceFence", 1L);
    assertDenied(token(numericFence, "RS256", KID, "JWT", keyPair));
  }

  @Test
  void preservesCanonicalCounterBeyondLongRangeAtVerifierBoundary() throws Exception {
    String beyondLong = "9223372036854775808";
    Map<String, Object> claims =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    claims.put("tokenGeneration", beyondLong);
    AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy exactCounterPolicy =
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
            ignored -> {});

    AccountAsymmetricJwtVerifier.VerifiedClaims verified =
        verifier.verify(token(claims, "RS256", KID, "JWT", keyPair), exactCounterPolicy);

    assertThat(verified.claims().get("tokenGeneration")).isEqualTo(beyondLong);
  }

  @Test
  void preservesSignedDelegationCountersAboveJavascriptIntegerPrecision() throws Exception {
    String largeCounter = "9007199254740993";
    Map<String, Object> claims =
        validClaims(NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120);
    claims.put("issuanceFence", largeCounter);
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", largeCounter);
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of());
    tuple.put("membershipAuthorityGeneration", Map.of());
    tuple.put("privateRealmGrantVersions", java.util.List.of());
    claims.put("authorityTuple", tuple);

    AccountAsymmetricJwtVerifier.VerifiedClaims verified =
        verifier.verify(token(claims, "RS256", KID, "JWT", keyPair), policy);

    assertThat(verified.claims().get("tokenGeneration")).isEqualTo("1");
    assertThat(verified.claims().get("issuanceFence")).isEqualTo(largeCounter);
    assertThat(verified.claims().get("authorityTuple")).isInstanceOf(Map.class);
    Map<?, ?> verifiedTuple = (Map<?, ?>) verified.claims().get("authorityTuple");
    assertThat(verifiedTuple.get("issuerAuthGeneration")).isEqualTo(largeCounter);
    assertThat(verifiedTuple.get("accountAuthorityGeneration")).isEqualTo("1");
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
    assertDenied(staleVerifier, valid);
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
        .isEqualTo(GameSessionAccountDelegationProfile.ISSUER);
    assertThat(rotationVerifier.verify(rotatedToken, policy).claims().get("iss"))
        .isEqualTo(GameSessionAccountDelegationProfile.ISSUER);
    assertThat(loads).hasValue(2);
  }

  @Test
  void claimShapeValidationRunsOnlyAfterSuccessfulSignatureVerification() throws Exception {
    AtomicInteger shapeValidations = new AtomicInteger();
    AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy observingPolicy =
        new AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy(
            "credential-authenticate-account",
            GameSessionAccountDelegationProfile.PROFILE,
            GameSessionAccountDelegationProfile.TYPE,
            GameSessionAccountDelegationProfile.ISSUER,
            GameSessionAccountDelegationProfile.AUDIENCE,
            CLAIMS,
            Set.of(),
            GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
            5,
            GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
            claims -> {
              shapeValidations.incrementAndGet();
              GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims);
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
        GameSessionAccountDelegationProfile.PROFILE,
        GameSessionAccountDelegationProfile.TYPE,
        GameSessionAccountDelegationProfile.ISSUER,
        GameSessionAccountDelegationProfile.AUDIENCE,
        CLAIMS,
        Set.of(),
        300,
        5,
        16 * 1024,
        GameSessionAccountDelegationJwtProfileValidator::validateClaims);
  }

  private static Map<String, Object> validClaims(long issuedAt, long notBefore, long expiresAt) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", GameSessionAccountDelegationProfile.ISSUER);
    claims.put("sub", "11111111-1111-4111-8111-111111111111");
    claims.put("jti", "22222222-2222-4222-8222-222222222222");
    claims.put("accountId", "11111111-1111-4111-8111-111111111111");
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
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
      } else if ("membershipVersion".equals(entry.getKey())) {
        json.append("{}");
      } else if ("authorityTuple".equals(entry.getKey())) {
        Map<?, ?> tuple = (Map<?, ?>) value;
        json.append("{\"issuerAuthGeneration\":\"")
            .append(tuple.get("issuerAuthGeneration"))
            .append("\",\"accountAuthorityGeneration\":\"")
            .append(tuple.get("accountAuthorityGeneration"))
            .append("\",\"tenantAuthorityGeneration\":{},")
            .append("\"membershipAuthorityGeneration\":{},\"privateRealmGrantVersions\":[]}");
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
