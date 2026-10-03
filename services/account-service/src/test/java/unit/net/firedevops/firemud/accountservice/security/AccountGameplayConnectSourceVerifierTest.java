package unit.net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.security.AccountGameplayConnectSourceVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayConnectSourceVerifierTest {
  private static final String ISSUER = "https://account.example.test";
  private static final String RSA_KID = "account-rsa-current";
  private static final String ED_KID = "account-ed-current";
  private static final int MAX_COMPACT_JWT_BYTES = 16 * 1024;
  private static final Instant NOW = Instant.parse("2026-04-06T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final long NOW_SECONDS = NOW.getEpochSecond();
  private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000001";
  private static final String TENANT_ID = "00000000-0000-4000-8000-000000000002";
  private static final String REALM_ID = "00000000-0000-4000-8000-000000000003";
  private static final String GAME_INSTANCE_ID = "00000000-0000-4000-8000-000000000004";
  private static final String NAMESPACE_ID = "00000000-0000-4000-8000-000000000005";
  private static final String TEST_LIFECYCLE_ID = "00000000-0000-4000-8000-000000000006";
  private static final BigInteger LARGE_ACCOUNT_GENERATION =
      new BigInteger("123456789012345678901234567890");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private KeyPair rsaKeyPair;
  private KeyPair edKeyPair;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    rsaKeyPair = rsa.generateKeyPair();
    edKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  @Test
  void verifiesRsaSourceAndPreservesExactDeeplyImmutableClaims() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    String compactJwt = sign(claims, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate());

    Map<String, Object> verified = verifier().verify(compactJwt);

    assertEquals(ISSUER, verified.get("iss"));
    assertEquals("gameplay-connect", verified.get("aud"));
    assertEquals(BigInteger.valueOf(NOW_SECONDS - 1), verified.get("iat"));
    assertEquals(BigInteger.valueOf(NOW_SECONDS + 29), verified.get("exp"));
    assertEquals(
        "19",
        verified.get("membershipVersion") instanceof Map<?, ?> map ? map.get(TENANT_ID) : null);
    assertEquals(
        LARGE_ACCOUNT_GENERATION,
        ((Map<?, ?>) verified.get("authorityTuple")).get("accountAuthorityGeneration"));
    assertFalse(verified.containsKey("rawJWT"));
    assertFalse(verified.containsValue(compactJwt));

    assertThrows(UnsupportedOperationException.class, () -> verified.put("extra", "value"));
    Map<String, Object> tuple = objectMap(verified.get("authorityTuple"));
    assertThrows(UnsupportedOperationException.class, () -> tuple.put("extra", "value"));
    Map<String, Object> tenantGenerations = objectMap(tuple.get("tenantAuthorityGeneration"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> tenantGenerations.put(TENANT_ID, BigInteger.TEN));
    Map<String, Object> billingCutoffs = objectMap(tuple.get("tenantBillingCutoff"));
    Map<String, Object> tenantCutoff = objectMap(billingCutoffs.get(TENANT_ID));
    assertThrows(
        UnsupportedOperationException.class,
        () -> tenantCutoff.put("outboxSequence", BigInteger.TEN));
  }

  @Test
  void verifiesEd25519SourceAndOptionalProtectedHeaderType() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    String compactJwt = sign(claims, "EdDSA", ED_KID, null, edKeyPair.getPrivate());
    AccountGameplayConnectSourceVerifier verifier =
        new AccountGameplayConnectSourceVerifier(
            ISSUER, Map.of(ED_KID, edKeyPair.getPublic()), MAX_COMPACT_JWT_BYTES, CLOCK);

    assertEquals("request-1", verifier.verify(compactJwt).get("requestId"));
  }

  @Test
  void rejectsRsaVerificationKeysBelow2048BitsAtConstruction() throws Exception {
    KeyPairGenerator weakRsa = KeyPairGenerator.getInstance("RSA");
    weakRsa.initialize(1024);
    KeyPair weakKeyPair = weakRsa.generateKeyPair();

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AccountGameplayConnectSourceVerifier(
                    ISSUER,
                    Map.of("account-rsa-weak", weakKeyPair.getPublic()),
                    MAX_COMPACT_JWT_BYTES,
                    CLOCK));

    assertEquals("Account verification key registry is invalid", failure.getMessage());
  }

  @Test
  void acceptsLifecycleBoundNonPublicTargetAndKeepsGrantValuesImmutable() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    claims.put("realmSlug", "preview");
    claims.put("playtestLifecycleId", TEST_LIFECYCLE_ID);
    claims.put("playtestStateGeneration", BigInteger.valueOf(3));
    Map<String, Object> tuple = objectMap(claims.get("authorityTuple"));
    tuple.put(
        "privateRealmGrantVersions",
        List.of(
            Map.of(
                "tenantId",
                TENANT_ID,
                "worldSlug",
                "emberfall",
                "realmSlug",
                "preview",
                "playtestLifecycleId",
                TEST_LIFECYCLE_ID,
                "grantVersion",
                BigInteger.valueOf(7))));
    String compactJwt = sign(claims, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate());

    Map<String, Object> verified = verifier().verify(compactJwt);

    Map<String, Object> verifiedTuple = objectMap(verified.get("authorityTuple"));
    List<?> grants = (List<?>) verifiedTuple.get("privateRealmGrantVersions");
    assertThrows(UnsupportedOperationException.class, grants::clear);
    Map<String, Object> grant = objectMap(grants.getFirst());
    assertThrows(
        UnsupportedOperationException.class, () -> grant.put("grantVersion", BigInteger.TEN));
  }

  @Test
  void rejectsUnknownKidWrongSignatureAndWrongIssuerWithOneSanitizedFailure() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    String unknownKid = sign(claims, "RS256", "other-account-key", "JWT", rsaKeyPair.getPrivate());
    KeyPair wrongPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    String wrongSignature = sign(claims, "RS256", RSA_KID, "JWT", wrongPair.getPrivate());
    Map<String, Object> wrongIssuerClaims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    wrongIssuerClaims.put("iss", "https://other-account.example.test");
    String wrongIssuer = sign(wrongIssuerClaims, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate());

    assertSanitizedFailure(unknownKid);
    assertSanitizedFailure(wrongSignature);
    assertSanitizedFailure(wrongIssuer);
  }

  @Test
  void rejectsExpiredFutureAndOverlongSourceTimesBeforeProjection() throws Exception {
    Map<String, Object> expired = sourceClaims(NOW_SECONDS - 5, NOW_SECONDS);
    Map<String, Object> future = sourceClaims(NOW_SECONDS + 6, NOW_SECONDS + 20);
    Map<String, Object> overlong = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 30);

    assertSanitizedFailure(sign(expired, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
    assertSanitizedFailure(sign(future, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
    assertSanitizedFailure(sign(overlong, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
  }

  @Test
  void rejectsNonCanonicalMalformedAndOversizedCompactJwts() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    String compactJwt = sign(claims, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate());
    String[] segments = compactJwt.split("\\.", -1);

    assertSanitizedFailure(null);
    assertSanitizedFailure("one.two");
    assertSanitizedFailure(compactJwt + ".fourth");
    assertSanitizedFailure(segments[0] + "=." + segments[1] + "." + segments[2]);
    AccountGameplayConnectSourceVerifier tinyLimitVerifier =
        new AccountGameplayConnectSourceVerifier(
            ISSUER, Map.of(RSA_KID, rsaKeyPair.getPublic()), 8, CLOCK);
    assertSanitizedFailure(tinyLimitVerifier, compactJwt);
  }

  @Test
  void rejectsDuplicateAndUnregisteredProtectedHeaderFields() throws Exception {
    String payload = JSON.writeValueAsString(sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29));
    String duplicateKidHeader =
        "{\"alg\":\"RS256\",\"kid\":\""
            + RSA_KID
            + "\",\"kid\":\""
            + RSA_KID
            + "\",\"typ\":\"JWT\"}";
    String remoteKeyHeader =
        "{\"alg\":\"RS256\",\"kid\":\""
            + RSA_KID
            + "\",\"typ\":\"JWT\",\"jku\":\"https://attacker.invalid/keys\"}";
    String criticalHeader =
        "{\"alg\":\"RS256\",\"kid\":\"" + RSA_KID + "\",\"typ\":\"JWT\",\"crit\":[]}";
    String embeddedKeyHeader =
        "{\"alg\":\"RS256\",\"kid\":\"" + RSA_KID + "\",\"typ\":\"JWT\",\"jwk\":{\"kty\":\"RSA\"}}";

    assertSanitizedFailure(
        signRaw(duplicateKidHeader, payload, "SHA256withRSA", rsaKeyPair.getPrivate()));
    assertSanitizedFailure(
        signRaw(remoteKeyHeader, payload, "SHA256withRSA", rsaKeyPair.getPrivate()));
    assertSanitizedFailure(
        signRaw(criticalHeader, payload, "SHA256withRSA", rsaKeyPair.getPrivate()));
    assertSanitizedFailure(
        signRaw(embeddedKeyHeader, payload, "SHA256withRSA", rsaKeyPair.getPrivate()));
  }

  @Test
  void rejectsDuplicateClaimsExtraFieldsWrongProfileAndMalformedNumericValues() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    String validPayload = JSON.writeValueAsString(claims);
    String duplicateClaimPayload =
        validPayload.replaceFirst(
            "\"iss\":\"[^\"]+\",", "\"iss\":\"" + ISSUER + "\",\"iss\":\"" + ISSUER + "\",");
    assertSanitizedFailure(
        signRaw(
            canonicalHeader(RSA_KID, "RS256", "JWT"),
            duplicateClaimPayload,
            "SHA256withRSA",
            rsaKeyPair.getPrivate()));

    Map<String, Object> extra = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    extra.put("rawJWT", "must-not-escape");
    assertSanitizedFailure(sign(extra, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));

    Map<String, Object> otherProfile = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    otherProfile.put("aud", "player-bootstrap");
    assertSanitizedFailure(sign(otherProfile, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));

    Map<String, Object> stringCounter = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    Map<String, Object> stringTuple = objectMap(stringCounter.get("authorityTuple"));
    stringTuple.put("tenantAuthorityGeneration", new LinkedHashMap<>(Map.of(TENANT_ID, "13")));
    assertSanitizedFailure(sign(stringCounter, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));

    Map<String, Object> fractionalTime = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    fractionalTime.put("iat", new BigDecimal(NOW_SECONDS - 1 + ".0"));
    assertSanitizedFailure(sign(fractionalTime, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
  }

  @Test
  void rejectsNonCanonicalMembershipVersionStringsAndJsonNumbers() throws Exception {
    for (Object malformedVersion :
        List.of(
            BigInteger.valueOf(19),
            new BigDecimal("19.0"),
            new BigDecimal("1E2"),
            "0",
            "00",
            "+19",
            "-19",
            " 19",
            "19 ",
            "1e2",
            "19.0",
            Boolean.TRUE)) {
      Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
      claims.put(
          "membershipVersion", java.util.Collections.singletonMap(TENANT_ID, malformedVersion));
      assertSanitizedFailure(sign(claims, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
    }

    Map<String, Object> nullVersion = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    nullVersion.put("membershipVersion", java.util.Collections.singletonMap(TENANT_ID, null));
    assertSanitizedFailure(sign(nullVersion, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));

    Map<String, Object> extraTenant = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    extraTenant.put(
        "membershipVersion", Map.of(TENANT_ID, "19", "00000000-0000-4000-8000-000000000099", "20"));
    assertSanitizedFailure(sign(extraTenant, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
  }

  @Test
  void rejectsSelectedTargetAndMembershipMapChanges() throws Exception {
    Map<String, Object> wrongTenant = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    wrongTenant.put("tenantId", "00000000-0000-4000-8000-000000000099");

    Map<String, Object> wrongMembershipKey = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    wrongMembershipKey.put(
        "membershipVersion",
        Map.of("00000000-0000-4000-8000-000000000099", BigInteger.valueOf(19)));

    assertSanitizedFailure(sign(wrongTenant, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
    assertSanitizedFailure(
        sign(wrongMembershipKey, "RS256", RSA_KID, "JWT", rsaKeyPair.getPrivate()));
  }

  @Test
  void rejectsAlgorithmAndKeyTypeMismatchAndNoneOrHmacAlgorithms() throws Exception {
    Map<String, Object> claims = sourceClaims(NOW_SECONDS - 1, NOW_SECONDS + 29);
    String payload = JSON.writeValueAsString(claims);
    String rsaWithEdAlgorithm =
        signRaw(
            canonicalHeader(RSA_KID, "EdDSA", "JWT"),
            payload,
            "SHA256withRSA",
            rsaKeyPair.getPrivate());
    String none =
        signRaw(
            canonicalHeader(RSA_KID, "none", "JWT"),
            payload,
            "SHA256withRSA",
            rsaKeyPair.getPrivate());
    String hmac =
        signRaw(
            canonicalHeader(RSA_KID, "HS256", "JWT"),
            payload,
            "SHA256withRSA",
            rsaKeyPair.getPrivate());

    assertSanitizedFailure(rsaWithEdAlgorithm);
    assertSanitizedFailure(none);
    assertSanitizedFailure(hmac);
  }

  private AccountGameplayConnectSourceVerifier verifier() {
    return new AccountGameplayConnectSourceVerifier(
        ISSUER, Map.of(RSA_KID, rsaKeyPair.getPublic()), MAX_COMPACT_JWT_BYTES, CLOCK);
  }

  private void assertSanitizedFailure(String compactJwt) {
    assertSanitizedFailure(verifier(), compactJwt);
  }

  private void assertSanitizedFailure(
      AccountGameplayConnectSourceVerifier verifier, String compactJwt) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(compactJwt));
    assertEquals("invalid Account gameplay-connect source JWT", failure.getMessage());
    assertNull(failure.getCause());
    if (compactJwt != null) {
      assertFalse(failure.getMessage().contains(compactJwt));
    }
  }

  private static Map<String, Object> sourceClaims(long issuedAt, long expiresAt) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", "gameplay-connect");
    claims.put("iat", BigInteger.valueOf(issuedAt));
    claims.put("exp", BigInteger.valueOf(expiresAt));
    claims.put("jti", "connect-jti-1");
    claims.put("accountId", ACCOUNT_ID);
    claims.put("tenantId", TENANT_ID);
    claims.put("realmId", REALM_ID);
    claims.put("worldSlug", "emberfall");
    claims.put("realmSlug", "public-production");
    claims.put("playableStateNamespaceId", NAMESPACE_ID);
    claims.put("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED");
    claims.put("gameInstanceId", GAME_INSTANCE_ID);
    claims.put("pointerVersion", BigInteger.valueOf(23));
    claims.put("catalogRevision", BigInteger.valueOf(41));
    claims.put("connectScopeId", "scope-1");
    claims.put("requestId", "request-1");
    claims.put("authorityTuple", authorityTuple());
    claims.put("membershipVersion", Map.of(TENANT_ID, "19"));
    claims.put("replayAdmissionFence", BigInteger.valueOf(29));
    return claims;
  }

  private static Map<String, Object> authorityTuple() {
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", BigInteger.valueOf(2));
    tuple.put("accountAuthorityGeneration", LARGE_ACCOUNT_GENERATION);
    tuple.put("tenantAuthorityGeneration", Map.of(TENANT_ID, BigInteger.valueOf(13)));
    tuple.put("membershipAuthorityGeneration", Map.of(TENANT_ID, BigInteger.valueOf(17)));
    tuple.put("privateRealmGrantVersions", List.of());
    tuple.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            LARGE_ACCOUNT_GENERATION,
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT_ID,
            "outboxSequence",
            BigInteger.valueOf(31)));
    tuple.put(
        "tenantBillingCutoff",
        Map.of(
            TENANT_ID,
            Map.of(
                "tenantAuthorityGeneration", BigInteger.valueOf(13),
                "tenantBillingSequence", BigInteger.valueOf(5),
                "outboxStreamKey", "account:auth-authority:v1:tenant/" + TENANT_ID,
                "outboxSequence", BigInteger.valueOf(37))));
    return tuple;
  }

  private static String sign(
      Map<String, Object> claims,
      String algorithm,
      String kid,
      String type,
      java.security.PrivateKey privateKey)
      throws Exception {
    String header = canonicalHeader(kid, algorithm, type);
    String payload = JSON.writeValueAsString(claims);
    String jcaAlgorithm =
        switch (algorithm) {
          case "RS256" -> "SHA256withRSA";
          case "RS384" -> "SHA384withRSA";
          case "RS512" -> "SHA512withRSA";
          case "EdDSA" -> "Ed25519";
          default -> "SHA256withRSA";
        };
    return signRaw(header, payload, jcaAlgorithm, privateKey);
  }

  private static String signRaw(
      String header, String payload, String jcaAlgorithm, java.security.PrivateKey privateKey)
      throws Exception {
    String protectedSegment = encode(header.getBytes(StandardCharsets.UTF_8));
    String payloadSegment = encode(payload.getBytes(StandardCharsets.UTF_8));
    byte[] signingInput =
        (protectedSegment + "." + payloadSegment).getBytes(StandardCharsets.US_ASCII);
    Signature signer = Signature.getInstance(jcaAlgorithm);
    signer.initSign(privateKey);
    signer.update(signingInput);
    return protectedSegment + "." + payloadSegment + "." + encode(signer.sign());
  }

  private static String canonicalHeader(String kid, String algorithm, String type) {
    if (type == null) {
      return "{\"alg\":\"" + algorithm + "\",\"kid\":\"" + kid + "\"}";
    }
    return "{\"alg\":\"" + algorithm + "\",\"kid\":\"" + kid + "\",\"typ\":\"" + type + "\"}";
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    assertInstanceOf(Map.class, value);
    return (Map<String, Object>) value;
  }
}
