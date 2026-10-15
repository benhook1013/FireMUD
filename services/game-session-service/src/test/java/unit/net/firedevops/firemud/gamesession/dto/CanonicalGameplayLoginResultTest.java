package net.firedevops.firemud.gamesession.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real RSA/JWKS verification with controlled keys/response evidence; no currentness or admission.
 */
class CanonicalGameplayLoginResultTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
  private static final long NOW = CLOCK.instant().getEpochSecond();
  private static final UUID ACCOUNT = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID REQUEST = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CONTEXT = UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID JTI = UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308");
  private static final String ACCOUNT_STREAM = "account:auth-authority:v1:account/" + ACCOUNT;
  private static final String ISSUER_STREAM =
      "account:auth-authority:v1:issuer/firemud-account-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static KeyPair keys;

  @BeforeAll
  static void generateControlledSigningKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    keys = generator.generateKeyPair();
  }

  @Test
  void preservesCompleteAggregateAndSeparatesNonSecretEvidence() throws Exception {
    var wire = response(claims());
    var result = decode(wire);
    var evidence = result.evidence();
    assertThat(
            Arrays.equals(
                result.compactJwtBytes(), wire.getAuthToken().getBytes(StandardCharsets.US_ASCII)))
        .isTrue();
    assertThat(evidence.accountId()).isEqualTo(ACCOUNT);
    assertThat(evidence.requestId()).isEqualTo(REQUEST);
    assertThat(evidence.callerContextId()).isEqualTo(CONTEXT);
    assertThat(evidence.tokenJti()).isEqualTo(JTI);
    assertThat(evidence.tokenSha256()).isEqualTo(wire.getTokenSha256());
    assertThat(evidence.tokenProfile()).isEqualTo(wire.getTokenProfile());
    assertThat(evidence.issuedAtEpochSecond()).isEqualTo(wire.getIssuedAtEpochSeconds());
    assertThat(evidence.notBeforeEpochSecond()).isEqualTo(wire.getNotBeforeEpochSeconds());
    assertThat(evidence.expiresAtEpochSecond()).isEqualTo(wire.getExpiresAtEpochSeconds());
    assertThat(evidence.tokenGeneration()).isEqualTo(wire.getTokenGeneration());
    assertThat(evidence.issuanceFence()).isEqualTo(wire.getIssuanceFence());
    assertThat(evidence.authorityTupleCanonicalJson())
        .isEqualTo(wire.getAuthorityTupleCanonicalJson());
    assertThat(evidence.outboxCheckpointsCanonicalJson())
        .isEqualTo(wire.getOutboxCheckpointsCanonicalJson());
    assertThat(evidence.outboxSourceEventEvidenceCanonicalJson())
        .isEqualTo(wire.getOutboxSourceEventEvidenceCanonicalJson());
    byte[] copy = result.compactJwtBytes();
    copy[0] = 0;
    assertThat(result.compactJwtBytes()[0]).isNotZero();
    assertThat(result.toString()).contains("redacted", "non-admitting").doesNotContain("eyJ");
    assertThat(evidence.getClass().getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .doesNotContain("authToken", "compactJwt", "credential", "password");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "account",
        "request",
        "context",
        "jti",
        "hash",
        "profile",
        "iat",
        "nbf",
        "exp",
        "generation",
        "fence",
        "unknown",
        "empty-token",
        "missing-tuple",
        "missing-checkpoints",
        "missing-events"
      })
  void rejectsEveryScalarMismatchOrMissingAggregate(String change) throws Exception {
    var wire = response(claims()).toBuilder();
    switch (change) {
      case "account" -> wire.setAccountId(UUID.randomUUID().toString());
      case "request" -> wire.setRequestId(UUID.randomUUID().toString());
      case "context" -> wire.setCallerContextId(UUID.randomUUID().toString());
      case "jti" -> wire.setTokenJti(UUID.randomUUID().toString());
      case "hash" -> wire.setTokenSha256("0".repeat(64));
      case "profile" -> wire.setTokenProfile("control-ui");
      case "iat" -> wire.setIssuedAtEpochSeconds(NOW - 1);
      case "nbf" -> wire.setNotBeforeEpochSeconds(NOW - 1);
      case "exp" -> wire.setExpiresAtEpochSeconds(NOW + 121);
      case "generation" -> wire.setTokenGeneration("2");
      case "fence" -> wire.setIssuanceFence("2");
      case "unknown" ->
          wire.setUnknownFields(
              UnknownFieldSet.newBuilder()
                  .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                  .build());
      case "empty-token" -> wire.clearAuthToken();
      case "missing-tuple" -> wire.clearAuthorityTupleCanonicalJson();
      case "missing-checkpoints" -> wire.clearOutboxCheckpointsCanonicalJson();
      case "missing-events" -> wire.clearOutboxSourceEventEvidenceCanonicalJson();
      default -> throw new AssertionError("Unknown test mutation");
    }
    assertInvalid(wire.build());
  }

  @ParameterizedTest
  @ValueSource(strings = {"iss", "aud", "sub", "expired", "future", "tenant", "shape", "signature"})
  void rejectsRealSignedWrongProfileExpiryScopeOrSignature(String change) throws Exception {
    var claims = claims();
    switch (change) {
      case "iss" -> claims.put("iss", "other-issuer");
      case "aud" -> claims.put("aud", "other-service");
      case "sub" -> claims.put("sub", UUID.randomUUID().toString());
      case "expired" -> {
        claims.put("iat", NOW - 120);
        claims.put("nbf", NOW - 120);
        claims.put("exp", NOW);
      }
      case "future" -> {
        claims.put("iat", NOW + 30);
        claims.put("nbf", NOW + 30);
        claims.put("exp", NOW + 150);
      }
      case "tenant" -> claims.put("tenantId", UUID.randomUUID().toString());
      case "shape" -> claims.put("tokenType", "private_player_delegation");
      case "signature" -> {}
      default -> throw new AssertionError("Unknown test mutation");
    }
    var wire = response(claims);
    if (change.equals("signature")) {
      String compact = wire.getAuthToken();
      int index = compact.lastIndexOf('.') + 1;
      compact =
          compact.substring(0, index)
              + (compact.charAt(index) == 'A' ? 'B' : 'A')
              + compact.substring(index + 1);
      wire = wire.toBuilder().setAuthToken(compact).setTokenSha256(hash(compact)).build();
    }
    assertInvalid(wire);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tuple",
        "stream",
        "order",
        "duplicate",
        "numeric-sequence",
        "extra-checkpoint",
        "event-sequence",
        "event-stream",
        "event-digest",
        "missing-positive",
        "event-on-zero",
        "extra-event",
        "noncanonical",
        "duplicate-json",
        "invalid-utf8",
        "oversize",
        "cutoff"
      })
  void rejectsAuthorityAndOutboxProjectionMismatch(String change) throws Exception {
    var claims = claims();
    if (change.equals("cutoff"))
      claims.put(
          "authorityTuple",
          GameSessionAccountDelegationProfile.authorityTuple(
              1,
              2,
              java.util.Optional.of(
                  new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                      "2", ACCOUNT_STREAM, "2"))));
    var wire = response(claims).toBuilder();
    var event = new LinkedHashMap<String, Object>(event());
    var first = new LinkedHashMap<String, Object>(checkpoint(ACCOUNT_STREAM, "0"));
    var second = new LinkedHashMap<String, Object>(checkpoint(ISSUER_STREAM, "1"));
    switch (change) {
      case "tuple" ->
          wire.setAuthorityTupleCanonicalJson(
              canonical(GameSessionAccountDelegationProfile.authorityTuple(2, 1)));
      case "stream" -> {
        first.put("outboxStreamKey", "wrong-stream");
        wire.setOutboxCheckpointsCanonicalJson(canonical(List.of(first, second)));
      }
      case "order" -> wire.setOutboxCheckpointsCanonicalJson(canonical(List.of(second, first)));
      case "duplicate" -> wire.setOutboxCheckpointsCanonicalJson(canonical(List.of(first, first)));
      case "numeric-sequence" -> {
        second.put("outboxSequence", 1);
        wire.setOutboxCheckpointsCanonicalJson(canonical(List.of(first, second)));
      }
      case "extra-checkpoint" -> {
        second.put("extra", "value");
        wire.setOutboxCheckpointsCanonicalJson(canonical(List.of(first, second)));
      }
      case "event-sequence" -> {
        event.put("outboxSequence", "2");
        wire.setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(event)));
      }
      case "event-stream" -> {
        event.put("outboxStreamKey", ACCOUNT_STREAM);
        wire.setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(event)));
      }
      case "event-digest" -> {
        event.put("sourceEventDigest", "bad");
        wire.setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(event)));
      }
      case "missing-positive" ->
          wire.setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of()));
      case "event-on-zero" -> {
        event.put("outboxStreamKey", ACCOUNT_STREAM);
        event.put("outboxSequence", "0");
        wire.setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(event)));
      }
      case "extra-event" ->
          wire.setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(event, event)));
      case "noncanonical" ->
          wire.setOutboxCheckpointsCanonicalJson(
              ByteString.copyFromUtf8(
                  " " + wire.getOutboxCheckpointsCanonicalJson().toStringUtf8()));
      case "duplicate-json" ->
          wire.setOutboxCheckpointsCanonicalJson(
              ByteString.copyFromUtf8(
                  "[{\"outboxStreamKey\":\"x\",\"outboxStreamKey\":\"y\"},{}]"));
      case "invalid-utf8" ->
          wire.setOutboxSourceEventEvidenceCanonicalJson(
              ByteString.copyFrom(new byte[] {(byte) 0xc3, 0x28}));
      case "oversize" ->
          wire.setOutboxCheckpointsCanonicalJson(ByteString.copyFrom(new byte[8193]));
      case "cutoff" -> {}
      default -> throw new AssertionError("Unknown test mutation");
    }
    assertInvalid(wire.build());
  }

  @Test
  void acceptsExplicitZeroBaselinesWithNoEventAndBothPositiveEvents() throws Exception {
    var zero =
        response(claims()).toBuilder()
            .setOutboxCheckpointsCanonicalJson(
                canonical(List.of(checkpoint(ACCOUNT_STREAM, "0"), checkpoint(ISSUER_STREAM, "0"))))
            .setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of()))
            .build();
    assertThat(decode(zero).evidence().outboxSourceEventEvidenceCanonicalJson())
        .isEqualTo(canonical(List.of()));
    var accountEvent = new LinkedHashMap<String, Object>(event());
    accountEvent.put("outboxStreamKey", ACCOUNT_STREAM);
    var both =
        response(claims()).toBuilder()
            .setOutboxCheckpointsCanonicalJson(
                canonical(List.of(checkpoint(ACCOUNT_STREAM, "1"), checkpoint(ISSUER_STREAM, "1"))))
            .setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(accountEvent, event())))
            .build();
    assertThat(decode(both).evidence().outboxSourceEventEvidenceCanonicalJson())
        .isEqualTo(both.getOutboxSourceEventEvidenceCanonicalJson());
  }

  @Test
  void acceptsOnlyTokenFreeDeclaredErrorAndRejectsMixedResponse() throws Exception {
    var error =
        ErrorDetail.newBuilder()
            .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
            .setMessage("Invalid credentials");
    assertThatThrownBy(() -> decode(AuthenticateResponse.newBuilder().setError(error).build()))
        .isInstanceOfSatisfying(
            CanonicalGameplayLoginResult.CredentialDecisionException.class,
            outcome ->
                assertThat(outcome.code()).isEqualTo(AuthenticationErrorCodes.INVALID_CREDENTIALS));
    assertInvalid(response(claims()).toBuilder().setError(error).build());
    assertInvalid(
        AuthenticateResponse.newBuilder().setError(error.setCode("unregistered-code")).build());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 77})
  void sourceModesCannotManufactureCredentialIngress(int mode) {
    var original =
        request().toBuilder()
            .setSourceContext(request().getSourceContext().toBuilder().setTransportClassValue(mode))
            .build();
    assertThatThrownBy(() -> CanonicalGameplayLoginResult.requireOriginalRequest(original))
        .isInstanceOf(CanonicalGameplayLoginResult.InvalidResultException.class);
  }

  private static CanonicalGameplayLoginResult decode(AuthenticateResponse wire) throws Exception {
    return CanonicalGameplayLoginResult.fromAuthenticatedResponse(
        wire, request(), verifier(), CLOCK);
  }

  private static void assertInvalid(AuthenticateResponse wire) throws Exception {
    var verifier = verifier();
    assertThatThrownBy(
            () ->
                CanonicalGameplayLoginResult.fromAuthenticatedResponse(
                    wire, request(), verifier, CLOCK))
        .isInstanceOf(CanonicalGameplayLoginResult.InvalidResultException.class)
        .hasMessage("Canonical LOGIN request or result is invalid");
  }

  private static AuthenticateRequest request() {
    return AuthenticateRequest.newBuilder()
        .setRequestId(REQUEST.toString())
        .setEmail("player@example.test")
        .setPassword("secret")
        .setSourceContext(
            GameplayLoginSourceContext.newBuilder()
                .setCallerContextId(CONTEXT.toString())
                .setCanonicalClientAddress("203.0.113.10")
                .setTransportClass(
                    GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
        .build();
  }

  private static LinkedHashMap<String, Object> claims() {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
            Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
            Map.entry("sub", ACCOUNT.toString()),
            Map.entry("accountId", ACCOUNT.toString()),
            Map.entry("jti", JTI.toString()),
            Map.entry("iat", NOW),
            Map.entry("nbf", NOW),
            Map.entry("exp", NOW + 120),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("authorityTuple", GameSessionAccountDelegationProfile.authorityTuple(1, 1))));
  }

  private static AuthenticateResponse response(Map<String, Object> claims) throws Exception {
    String token = token(claims);
    return AuthenticateResponse.newBuilder()
        .setAuthToken(token)
        .setAccountId(ACCOUNT.toString())
        .setRequestId(REQUEST.toString())
        .setCallerContextId(CONTEXT.toString())
        .setTokenJti(JTI.toString())
        .setTokenSha256(hash(token))
        .setTokenProfile(GameSessionAccountDelegationProfile.PROFILE)
        .setIssuedAtEpochSeconds(((Number) claims.get("iat")).longValue())
        .setNotBeforeEpochSeconds(((Number) claims.get("nbf")).longValue())
        .setExpiresAtEpochSeconds(((Number) claims.get("exp")).longValue())
        .setTokenGeneration("1")
        .setIssuanceFence("1")
        .setAuthorityTupleCanonicalJson(canonical(claims.get("authorityTuple")))
        .setOutboxCheckpointsCanonicalJson(
            canonical(List.of(checkpoint(ACCOUNT_STREAM, "0"), checkpoint(ISSUER_STREAM, "1"))))
        .setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of(event())))
        .build();
  }

  private static Map<String, Object> checkpoint(String stream, String sequence) {
    return Map.of("outboxStreamKey", stream, "outboxSequence", sequence);
  }

  private static Map<String, Object> event() {
    return Map.of(
        "outboxStreamKey",
        ISSUER_STREAM,
        "outboxSequence",
        "1",
        "sourceEventId",
        "issuer-event-1",
        "sourceEventDigest",
        "sha256:" + "e".repeat(64));
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
  }

  private static String hash(String token) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String token(Map<String, Object> claims) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input =
        encoder.encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(JSON.writeValueAsBytes(claims));
    var signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(input.getBytes(StandardCharsets.US_ASCII));
    return input + "." + encoder.encodeToString(signature.sign());
  }

  private static AccountAsymmetricJwtVerifier verifier() throws Exception {
    var pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test",
            "cluster-a",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "binding-7",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    var key = (RSAPublicKey) keys.getPublic();
    String jwks =
        JSON.writeValueAsString(
            Map.of(
                "keys",
                List.of(
                    Map.of(
                        "kty",
                        "RSA",
                        "use",
                        "sig",
                        "alg",
                        "RS256",
                        "kid",
                        "test-key",
                        "n",
                        unsigned(key.getModulus().toByteArray()),
                        "e",
                        unsigned(key.getPublicExponent().toByteArray())))));
    var cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    pin, jwks.getBytes(StandardCharsets.UTF_8)),
            pin,
            CLOCK,
            Duration.ofSeconds(30));
    return new AccountAsymmetricJwtVerifier(cache, CLOCK);
  }

  private static String unsigned(byte[] bytes) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
}
