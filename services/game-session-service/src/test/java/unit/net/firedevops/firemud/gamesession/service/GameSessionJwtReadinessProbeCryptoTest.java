package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GameSessionJwtReadinessProbeCryptoTest {
  private static final Instant NOW = Instant.parse("2030-06-01T00:10:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
  private static final String KID = "pending-key-7";
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private KeyPair signingKey;
  private GameSessionJwtReadinessProbeCrypto crypto;
  private final GameSessionJwtReadinessReceiverProtoMapper mapper =
      new GameSessionJwtReadinessReceiverProtoMapper();

  @BeforeEach
  void setUp() throws Exception {
    signingKey = rsa3072();
    AccountPublicJwksCache.SourceIdentity source = sourceIdentity();
    String jwks = jwks(KID, (RSAPublicKey) signingKey.getPublic());
    AccountPublicJwksCache cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    source, jwks.getBytes(StandardCharsets.US_ASCII)),
            source,
            CLOCK,
            Duration.ofSeconds(300));
    crypto =
        new GameSessionJwtReadinessProbeCrypto(new AccountAsymmetricJwtVerifier(cache, CLOCK), 8);
  }

  @Test
  void verifiesRealProductionDelegationAndCanaryTokensAsNonAuthorizingProofs() throws Exception {
    for (String profile :
        List.of(
            GameSessionAccountDelegationProfile.PROFILE,
            GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE)) {
      ReadinessProbeCoordinates.ExpectedOutcome expected =
          ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT;
      UUID jti = UUID.randomUUID();
      var parsed = mapper.parse(request(profile, expected, signedToken(profile, jti), jti).build());

      var verified = crypto.verify(parsed);

      assertThat(verified.keyId()).isEqualTo(KID);
      assertThat(verified.outcome())
          .isEqualTo(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED);
    }
  }

  @Test
  void verifiesValidInapplicableProductionProfilesButReturnsOnlyInapplicableReject()
      throws Exception {
    for (String profile :
        List.of(ControlUiJwtProfileValidator.PROFILE, PlayerBootstrapJwtProfileValidator.PROFILE)) {
      UUID jti = UUID.randomUUID();
      var parsed =
          mapper.parse(
              request(
                      profile,
                      ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT,
                      signedToken(profile, jti),
                      jti)
                  .build());

      var verified = crypto.verify(parsed);

      assertThat(verified.outcome())
          .isEqualTo(ReceiveReadinessProbeResponse.ObservationOutcome.INAPPLICABLE_REJECT);
    }
  }

  @Test
  void rejectsSignatureOrExpectedOutcomeMismatch() throws Exception {
    UUID jti = UUID.randomUUID();
    String valid = signedToken(GameSessionAccountDelegationProfile.PROFILE, jti);
    String[] parts = valid.split("\\.", -1);
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 1;
    String tampered = parts[0] + "." + parts[1] + "." + encode(signature);
    var tamperedProbe =
        mapper.parse(
            request(
                    GameSessionAccountDelegationProfile.PROFILE,
                    ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT,
                    tampered,
                    jti)
                .build());
    assertThatThrownBy(() -> crypto.verify(tamperedProbe))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);

    var mismatchedProbe =
        mapper.parse(
            request(
                    GameSessionAccountDelegationProfile.PROFILE,
                    ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT,
                    valid,
                    jti)
                .build());
    var alteredExpectation =
        new GameSessionJwtReadinessReceiverProtoMapper.ParsedRequest(
            mismatchedProbe.coordinates(),
            mismatchedProbe.operationId(),
            mismatchedProbe.jti(),
            mismatchedProbe.probeKind(),
            mismatchedProbe.expectedActive(),
            ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT,
            mismatchedProbe.compactJwt(),
            mismatchedProbe.compactTokenSha256());
    assertThatThrownBy(() -> crypto.verify(alteredExpectation))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
  }

  private static net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest.Builder request(
      String profile, ReadinessProbeCoordinates.ExpectedOutcome outcome, String token, UUID jti) {
    boolean canary = GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE.equals(profile);
    String audience =
        switch (profile) {
          case GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE ->
              GameSessionJwtReadinessReceiverProtoMapper.CANARY_AUDIENCE;
          case ControlUiJwtProfileValidator.PROFILE -> ControlUiJwtProfileValidator.AUDIENCE;
          case PlayerBootstrapJwtProfileValidator.PROFILE ->
              PlayerBootstrapJwtProfileValidator.AUDIENCE;
          default -> GameSessionAccountDelegationProfile.AUDIENCE;
        };
    long issuedAt = NOW.getEpochSecond() - 1L;
    ReadinessProbeCoordinates coordinates =
        ReadinessProbeCoordinates.newBuilder()
            .setRotationOperationId(UUID.randomUUID().toString())
            .setOperationDigest("a".repeat(64))
            .setPlanDigest("b".repeat(64))
            .setPlanVersion(2)
            .setRegistryVersion(1)
            .setValidatorId(GameSessionJwtReadinessReceiverProtoMapper.GAME_SESSION_VALIDATOR)
            .setTokenProfile(profile)
            .setAudience(audience)
            .setProbeKind(
                canary
                    ? ReadinessProbeCoordinates.ProbeKind.CANARY
                    : ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
            .setExpectedOutcome(outcome)
            .setJti(jti.toString())
            .setEntryVersion(1)
            .setTargetGeneration("7")
            .setTargetKid(KID)
            .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
            .setIssuedAtEpochSeconds(issuedAt)
            .setExpiresAtEpochSeconds(issuedAt + 120L)
            .setPlanExpiresAtEpochSeconds(issuedAt + 180L)
            .build();
    return net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest.newBuilder()
        .setSchemaVersion(1)
        .setCoordinates(coordinates)
        .setCompactJwt(token);
  }

  private String signedToken(String profile, UUID jti) throws Exception {
    boolean canary = GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE.equals(profile);
    String audience =
        switch (profile) {
          case GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE ->
              GameSessionJwtReadinessReceiverProtoMapper.CANARY_AUDIENCE;
          case ControlUiJwtProfileValidator.PROFILE -> ControlUiJwtProfileValidator.AUDIENCE;
          case PlayerBootstrapJwtProfileValidator.PROFILE ->
              PlayerBootstrapJwtProfileValidator.AUDIENCE;
          default -> GameSessionAccountDelegationProfile.AUDIENCE;
        };
    long issuedAt = NOW.getEpochSecond() - 1L;
    long expiresAt = issuedAt + 120L;
    Map<String, Object> header = Map.of("alg", "RS256", "kid", KID, "typ", "JWT");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", RESERVED_SUBJECT);
    claims.put("jti", jti.toString());
    claims.put("aud", audience);
    claims.put("iat", issuedAt);
    claims.put("nbf", issuedAt);
    claims.put("exp", expiresAt);
    if (canary) {
      claims.put("tokenProfile", profile);
      claims.put("tokenType", "account_jwt_readiness_canary");
    } else {
      if (ControlUiJwtProfileValidator.PROFILE.equals(profile)
          || PlayerBootstrapJwtProfileValidator.PROFILE.equals(profile)) {
        claims.put("scopedRoles", Map.of());
      }
      claims.put("accountId", RESERVED_SUBJECT);
      boolean decimalCounters = GameSessionAccountDelegationProfile.PROFILE.equals(profile);
      Object initial = decimalCounters ? "1" : 1L;
      claims.put("tokenGeneration", initial);
      claims.put("authorityTuple", authorityTuple(initial));
      claims.put("membershipVersion", Map.of());
      claims.put("issuanceFence", initial);
    }
    String signingInput =
        encode(JSON.writeValueAsBytes(header))
            + "."
            + encode(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(claims)));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(signingKey.getPrivate());
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + encode(signature.sign());
  }

  private static Map<String, Object> authorityTuple(Object generation) {
    return Map.of(
        "issuerAuthGeneration", generation,
        "accountAuthorityGeneration", generation,
        "tenantAuthorityGeneration", Map.of(),
        "membershipAuthorityGeneration", Map.of(),
        "privateRealmGrantVersions", List.of());
  }

  private static AccountPublicJwksCache.SourceIdentity sourceIdentity() {
    return new AccountPublicJwksCache.SourceIdentity(
        "prod",
        "cluster-a",
        "11111111-1111-4111-8111-111111111111",
        "firemud-prod",
        "22222222-2222-4222-8222-222222222222",
        "55555555-5555-4555-8555-555555555555",
        "account-api-r1",
        "https://kubernetes.example.test:6443",
        "9".repeat(64));
  }

  private static String jwks(String kid, RSAPublicKey key) {
    return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\""
        + kid
        + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
        + encode(unsigned(key.getModulus()))
        + "\",\"e\":\""
        + encode(unsigned(key.getPublicExponent()))
        + "\"}]}";
  }

  private static KeyPair rsa3072() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(new RSAKeyGenParameterSpec(3_072, RSAKeyGenParameterSpec.F4));
    return generator.generateKeyPair();
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return bytes.length > 1 && bytes[0] == 0
        ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length)
        : bytes;
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
