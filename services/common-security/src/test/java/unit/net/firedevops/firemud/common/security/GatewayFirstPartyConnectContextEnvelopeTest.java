package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class GatewayFirstPartyConnectContextEnvelopeTest {
  private static final String KEY_ID = "gateway-key-1";
  private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID TENANT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID REALM_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
  private static final UUID NAMESPACE_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("50000000-0000-4000-8000-000000000005");
  private static final UUID LIFECYCLE_ID = UUID.fromString("60000000-0000-4000-8000-000000000006");
  private static final long NOW_MILLIS = 1_800_000_000_000L;
  private static final String MAX_BIGINT = "9223372036854775807";
  private static final String SAFE_INTEGER = "9007199254740991";
  private static final String FIRST_WIDE_INTEGER = "9007199254740992";
  private static final String FIRST_WIDE_INTEGER_PLUS_ONE = "9007199254740993";

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private KeyPair keyPair;
  private GatewayFirstPartyConnectContextEnvelope.GatewaySigningKey signingKey;
  private GatewayFirstPartyConnectContextEnvelope.GatewayVerificationKeySource keySource;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2_048);
    keyPair = generator.generateKeyPair();
    signingKey =
        new GatewayFirstPartyConnectContextEnvelope.GatewaySigningKey(KEY_ID, keyPair.getPrivate());
    keySource = kid -> KEY_ID.equals(kid) ? (RSAPublicKey) keyPair.getPublic() : null;
  }

  @Test
  void signsAndVerifiesCompletePublicContextWithoutChangingCopiedAccountClaims() throws Exception {
    var source =
        source(
            SAFE_INTEGER, FIRST_WIDE_INTEGER, SAFE_INTEGER, FIRST_WIDE_INTEGER, MAX_BIGINT, false);

    String compact = GatewayFirstPartyConnectContextEnvelope.sign(source, metadata(), signingKey);
    var verified =
        GatewayFirstPartyConnectContextEnvelope.verify(compact, keySource, NOW_MILLIS, 5_000);
    Map<String, Object> payload = payload(compact);

    assertThat(verified.keyId()).isEqualTo(KEY_ID);
    assertThat(verified.claims().accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(verified.claims().tenantId()).isEqualTo(TENANT_ID);
    assertThat(payload)
        .containsEntry("audience", "game-session")
        .containsEntry("recipient", "game-session-service")
        .containsEntry("transportClass", "first_party_web")
        .containsEntry("pointerVersion", SAFE_INTEGER)
        .containsEntry("catalogRevision", FIRST_WIDE_INTEGER)
        .containsEntry("replayAdmissionFence", MAX_BIGINT)
        .containsEntry("canonicalClientAddress", "203.0.113.8")
        .containsEntry("gatewayRequestId", "webflux-request-17")
        .doesNotContainKeys(
            "iss", "requestId", "sourceExpiresAtEpochSeconds", "playtestLifecycleId");
    assertThat(payload.get("authorityTuple")).isEqualTo(authorityTupleMap(source.authorityTuple()));
    assertThat(payload.get("membershipVersion"))
        .isEqualTo(Map.of(TENANT_ID.toString(), MAX_BIGINT));
    assertThat((String) payload.get("connectRequestId")).isEqualTo("request-operation-17");
    assertThat(verified.claims().replayAdmissionFence()).isEqualTo(MAX_BIGINT);
  }

  @Test
  void signsExactLifecycleGrantAndGenerationForNonPublicTarget() throws Exception {
    var source = source("8", "9", MAX_BIGINT, MAX_BIGINT, "9", true);

    String compact = GatewayFirstPartyConnectContextEnvelope.sign(source, metadata(), signingKey);
    var verified =
        GatewayFirstPartyConnectContextEnvelope.verify(compact, keySource, NOW_MILLIS, 0);
    Map<String, Object> payload = payload(compact);

    assertThat(verified.claims().playtestLifecycleId()).isEqualTo(LIFECYCLE_ID.toString());
    assertThat(verified.claims().playtestStateGeneration()).isEqualTo("9");
    assertThat(payload.get("playtestLifecycleId")).isEqualTo(LIFECYCLE_ID.toString());
    assertThat(payload.get("playtestStateGeneration")).isEqualTo("9");
    assertThat(payload.get("authorityTuple")).isEqualTo(authorityTupleMap(source.authorityTuple()));
  }

  @Test
  void acceptsSafeBoundaryAdjacentWideValuesAndOwnerMaximumAsCanonicalStrings() {
    assertThat(source(SAFE_INTEGER, SAFE_INTEGER, SAFE_INTEGER, SAFE_INTEGER, SAFE_INTEGER, false))
        .extracting(
            GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource::pointerVersion,
            GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource::catalogRevision,
            GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource::replayAdmissionFence)
        .containsExactly(SAFE_INTEGER, SAFE_INTEGER, SAFE_INTEGER);
    assertThat(
            source(
                    FIRST_WIDE_INTEGER,
                    FIRST_WIDE_INTEGER,
                    FIRST_WIDE_INTEGER,
                    FIRST_WIDE_INTEGER,
                    FIRST_WIDE_INTEGER,
                    false)
                .pointerVersion())
        .isEqualTo(FIRST_WIDE_INTEGER);
    var adjacentWideSource =
        source(
            FIRST_WIDE_INTEGER_PLUS_ONE,
            FIRST_WIDE_INTEGER_PLUS_ONE,
            FIRST_WIDE_INTEGER_PLUS_ONE,
            FIRST_WIDE_INTEGER_PLUS_ONE,
            FIRST_WIDE_INTEGER_PLUS_ONE,
            false);
    String adjacentWideCompact =
        GatewayFirstPartyConnectContextEnvelope.sign(adjacentWideSource, metadata(), signingKey);
    var adjacentWideVerified =
        GatewayFirstPartyConnectContextEnvelope.verify(
            adjacentWideCompact, keySource, NOW_MILLIS, 0);
    Map<String, Object> adjacentWidePayload = payload(adjacentWideCompact);
    assertThat(adjacentWidePayload)
        .containsEntry("pointerVersion", FIRST_WIDE_INTEGER_PLUS_ONE)
        .containsEntry("catalogRevision", FIRST_WIDE_INTEGER_PLUS_ONE)
        .containsEntry("replayAdmissionFence", FIRST_WIDE_INTEGER_PLUS_ONE);
    assertThat(adjacentWidePayload.get("authorityTuple"))
        .isEqualTo(authorityTupleMap(adjacentWideSource.authorityTuple()));
    assertThat(adjacentWideVerified.claims().pointerVersion())
        .isEqualTo(FIRST_WIDE_INTEGER_PLUS_ONE);
    var maximumSource = source(MAX_BIGINT, MAX_BIGINT, MAX_BIGINT, MAX_BIGINT, MAX_BIGINT, false);
    String maximumCompact =
        GatewayFirstPartyConnectContextEnvelope.sign(maximumSource, metadata(), signingKey);
    var maximumVerified =
        GatewayFirstPartyConnectContextEnvelope.verify(maximumCompact, keySource, NOW_MILLIS, 0);
    Map<String, Object> maximumPayload = payload(maximumCompact);
    assertThat(maximumPayload)
        .containsEntry("pointerVersion", MAX_BIGINT)
        .containsEntry("catalogRevision", MAX_BIGINT)
        .containsEntry("replayAdmissionFence", MAX_BIGINT);
    assertThat(maximumPayload.get("authorityTuple"))
        .isEqualTo(authorityTupleMap(maximumSource.authorityTuple()));
    assertThat(maximumVerified.claims().pointerVersion()).isEqualTo(MAX_BIGINT);
  }

  @Test
  void rejectsMalformedCounterAliasesAndValuesAboveTheOwnerBigintMaximum() {
    for (String malformed :
        List.of("0", "00", "+1", "01", "1.0", "1e0", " 1", "9223372036854775808")) {
      assertThatThrownBy(() -> source(malformed, "1", "1", "1", "1", false))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> source("1", "1", "1", "1", "0", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> source("1", "1", "1", "1", "9223372036854775808", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMapKeyMismatchExtraTenantAndWrongNestedCounterType() {
    var tuple = authorityTuple("1", "1", "1", "1", false);
    assertThatThrownBy(
            () ->
                new GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource(
                    ACCOUNT_ID,
                    TENANT_ID,
                    REALM_ID,
                    "world",
                    "realm",
                    NAMESPACE_ID,
                    "SHARED",
                    GAME_INSTANCE_ID,
                    "1",
                    "1",
                    "scope-1",
                    "request-1",
                    tuple,
                    Map.of(UUID.randomUUID().toString(), "1"),
                    "jti-1",
                    "1",
                    null,
                    null,
                    NOW_MILLIS / 1_000L + 30L))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, String> extraTenant = new LinkedHashMap<>();
    extraTenant.put(TENANT_ID.toString(), "1");
    extraTenant.put(UUID.randomUUID().toString(), "2");
    assertThatThrownBy(
            () ->
                new GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource(
                    ACCOUNT_ID,
                    TENANT_ID,
                    REALM_ID,
                    "world",
                    "realm",
                    NAMESPACE_ID,
                    "SHARED",
                    GAME_INSTANCE_ID,
                    "1",
                    "1",
                    "scope-1",
                    "request-1",
                    tuple,
                    extraTenant,
                    "jti-1",
                    "1",
                    null,
                    null,
                    NOW_MILLIS / 1_000L + 30L))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> unsafeAuthority = new LinkedHashMap<>(authorityTupleMap(tuple));
    unsafeAuthority.put("issuerAuthGeneration", 1L);
    Map<String, Object> payload = payloadMap(source("1", "1", "1", "1", "1", false));
    payload.put("authorityTuple", unsafeAuthority);
    String compact = token(payload);
    assertRejected(compact, GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SCHEMA);

    unsafeAuthority.put("issuerAuthGeneration", 1.0d);
    payload.put("authorityTuple", unsafeAuthority);
    assertRejected(
        token(payload), GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SCHEMA);

    payload = payloadMap(source("1", "1", "1", "1", "1", false));
    payload.put("pointerVersion", 9_007_199_254_740_991L);
    assertRejected(
        token(payload), GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SCHEMA);

    payload = payloadMap(source("1", "1", "1", "1", "1", false));
    payload.put("replayAdmissionFence", "01");
    assertRejected(
        token(payload), GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SCHEMA);
  }

  @Test
  void validatesOptionalCutoffPresenceAndPositiveSequenceValues() {
    String tenant = TENANT_ID.toString();
    var cutoff =
        new GatewayFirstPartyConnectContextEnvelope.AccountSecurityCutoff(
            MAX_BIGINT, "account:auth-authority:v1:account/" + ACCOUNT_ID, MAX_BIGINT);
    var billing =
        new GatewayFirstPartyConnectContextEnvelope.TenantBillingCutoff(
            MAX_BIGINT,
            "9223372036854775806",
            "account:auth-authority:v1:tenant/" + TENANT_ID,
            "1");
    var tuple =
        new GatewayFirstPartyConnectContextEnvelope.AuthorityTuple(
            MAX_BIGINT,
            MAX_BIGINT,
            Map.of(tenant, MAX_BIGINT),
            Map.of(tenant, MAX_BIGINT),
            List.of(),
            cutoff,
            Map.of(tenant, billing));
    var valid = sourceWithTuple(tuple, MAX_BIGINT);
    String compact = GatewayFirstPartyConnectContextEnvelope.sign(valid, metadata(), signingKey);
    assertThat(payload(compact).get("authorityTuple")).isEqualTo(authorityTupleMap(tuple));

    assertThatThrownBy(
            () ->
                new GatewayFirstPartyConnectContextEnvelope.AccountSecurityCutoff(
                    "2", "account:auth-authority:v1:account/" + ACCOUNT_ID, "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GatewayFirstPartyConnectContextEnvelope.AuthorityTuple(
                    "1", "2", Map.of(tenant, "1"), Map.of(tenant, "1"), List.of(), null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongProtectedTypeAlgorithmUnknownHeadersAndUnknownKeyIds() throws Exception {
    Map<String, Object> payload = payloadMap(source("1", "1", "1", "1", "1", false));
    assertRejected(
        token(payload, Map.of("alg", "RS256", "kid", KEY_ID, "typ", "JWT")),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_HEADER);
    assertRejected(
        token(
            payload,
            Map.of(
                "alg",
                "HS256",
                "kid",
                KEY_ID,
                "typ",
                GatewayFirstPartyConnectContextEnvelope.PROTECTED_TYPE)),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_HEADER);
    assertRejected(
        token(
            payload,
            Map.of(
                "alg",
                "RS256",
                "kid",
                KEY_ID,
                "typ",
                GatewayFirstPartyConnectContextEnvelope.PROTECTED_TYPE,
                "crit",
                List.of("x"))),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_HEADER);

    String unknownKey =
        token(
            payload,
            Map.of(
                "alg",
                "RS256",
                "kid",
                "unknown-key",
                "typ",
                GatewayFirstPartyConnectContextEnvelope.PROTECTED_TYPE));
    assertRejected(
        unknownKey, GatewayFirstPartyConnectContextEnvelope.FailureReason.UNKNOWN_KEY_ID);
  }

  @Test
  void rejectsAudienceRecipientMissingFieldsUnknownFieldsAndAlteredSignature() throws Exception {
    Map<String, Object> base = payloadMap(source("1", "1", "1", "1", "1", false));
    Map<String, Object> wrongAudience = new LinkedHashMap<>(base);
    wrongAudience.put("audience", "internal-service");
    assertRejected(
        token(wrongAudience),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.AUDIENCE_MISMATCH);
    Map<String, Object> wrongRecipient = new LinkedHashMap<>(base);
    wrongRecipient.put("recipient", "account-service");
    assertRejected(
        token(wrongRecipient),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.RECIPIENT_MISMATCH);
    Map<String, Object> missing = new LinkedHashMap<>(base);
    missing.remove("accountId");
    assertRejected(
        token(missing), GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SCHEMA);
    Map<String, Object> extra = new LinkedHashMap<>(base);
    extra.put("issuer", "not-allowed");
    assertRejected(
        token(extra), GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SCHEMA);

    String valid =
        GatewayFirstPartyConnectContextEnvelope.sign(
            source("1", "1", "1", "1", "1", false), metadata(), signingKey);
    String[] parts = valid.split("\\.");
    String altered =
        parts[0]
            + "."
            + parts[1]
            + "."
            + (parts[2].charAt(0) == 'A' ? "B" : "A")
            + parts[2].substring(1);
    assertRejected(
        altered, GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsDuplicateAndNoncanonicalJsonAndPaddedBase64() throws Exception {
    String header =
        canonical(
            Map.of(
                "alg",
                "RS256",
                "kid",
                KEY_ID,
                "typ",
                GatewayFirstPartyConnectContextEnvelope.PROTECTED_TYPE));
    String duplicatePayload = "{\"accountId\":\"one\",\"accountId\":\"two\"}";
    assertRejected(
        compactRaw(header, duplicatePayload),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.MALFORMED_JSON);

    String validPayload = canonical(payloadMap(source("1", "1", "1", "1", "1", false)));
    String noncanonical = " " + validPayload;
    assertRejected(
        compactRaw(header, noncanonical),
        GatewayFirstPartyConnectContextEnvelope.FailureReason.NON_CANONICAL_ENCODING);

    String valid =
        GatewayFirstPartyConnectContextEnvelope.sign(
            source("1", "1", "1", "1", "1", false), metadata(), signingKey);
    String[] parts = valid.split("\\.");
    assertRejected(
        parts[0] + "=." + parts[1] + "." + parts[2],
        GatewayFirstPartyConnectContextEnvelope.FailureReason.MALFORMED_COMPACT);
  }

  @Test
  void enforcesSourceExpiryThirtySecondLifetimeSafeTimeIntegersAndClockSkew() {
    var source = source("1", "1", "1", "1", "1", false);
    var metadataPastSource =
        new GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata(
            "203.0.113.8", "request-1", NOW_MILLIS, NOW_MILLIS, NOW_MILLIS + 31_000L);
    assertThatThrownBy(
            () ->
                GatewayFirstPartyConnectContextEnvelope.sign(
                    source, metadataPastSource, signingKey))
        .isInstanceOf(GatewayFirstPartyConnectContextEnvelope.VerificationException.class)
        .extracting("reason")
        .isEqualTo(GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_LIFETIME);

    var tooLongSource =
        new GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource(
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
            source.playtestLifecycleId(),
            source.playtestStateGeneration(),
            NOW_MILLIS / 1_000L + 31L);
    var metadata31 =
        new GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata(
            "203.0.113.8", "request-1", NOW_MILLIS, NOW_MILLIS, NOW_MILLIS + 31_000L);
    assertThatThrownBy(
            () ->
                GatewayFirstPartyConnectContextEnvelope.sign(tooLongSource, metadata31, signingKey))
        .isInstanceOf(GatewayFirstPartyConnectContextEnvelope.VerificationException.class);

    String valid = GatewayFirstPartyConnectContextEnvelope.sign(source, metadata(), signingKey);
    assertRejectedAt(
        valid,
        NOW_MILLIS + 30_001L,
        0,
        GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_LIFETIME);
    assertRejectedAt(
        valid,
        NOW_MILLIS,
        5_001,
        GatewayFirstPartyConnectContextEnvelope.FailureReason.INVALID_LIFETIME);
  }

  @Test
  void failsClosedForUnavailableDedicatedKeySourceWithoutLeakingInput() throws Exception {
    String secretMarker = "sensitive-context-value";
    Map<String, Object> payload = payloadMap(source("1", "1", "1", "1", "1", false));
    payload.put("connectScopeId", secretMarker);
    String token =
        token(
            payload,
            Map.of(
                "alg",
                "RS256",
                "kid",
                "missing-key",
                "typ",
                GatewayFirstPartyConnectContextEnvelope.PROTECTED_TYPE));
    assertThatThrownBy(
            () ->
                GatewayFirstPartyConnectContextEnvelope.verify(
                    token,
                    kid -> {
                      throw new GatewayFirstPartyConnectContextEnvelope
                          .KeySourceUnavailableException();
                    },
                    NOW_MILLIS,
                    0))
        .isInstanceOf(GatewayFirstPartyConnectContextEnvelope.VerificationException.class)
        .satisfies(
            failure -> {
              assertThat(failure.getMessage()).doesNotContain(secretMarker, "missing-key", token);
              assertThat(
                      ((GatewayFirstPartyConnectContextEnvelope.VerificationException) failure)
                          .reason())
                  .isEqualTo(
                      GatewayFirstPartyConnectContextEnvelope.FailureReason.KEY_SOURCE_UNAVAILABLE);
            });
  }

  @Test
  void rejectsNoncanonicalClientAddressInsteadOfAcceptingHeaderLikeInput() {
    assertThatThrownBy(
            () ->
                new GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata(
                    "203.000.113.8", "request-1", NOW_MILLIS, NOW_MILLIS, NOW_MILLIS + 30_000L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata(
                    "198.51.100.2, 203.0.113.8",
                    "request-1",
                    NOW_MILLIS,
                    NOW_MILLIS,
                    NOW_MILLIS + 30_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource source(
      String pointerVersion,
      String catalogRevision,
      String generation,
      String membershipVersion,
      String replayFence,
      boolean privateTarget) {
    var tuple =
        authorityTuple(generation, generation, generation, membershipVersion, privateTarget);
    return sourceWithTuple(tuple, replayFence, pointerVersion, catalogRevision, privateTarget);
  }

  private GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource sourceWithTuple(
      GatewayFirstPartyConnectContextEnvelope.AuthorityTuple tuple, String membershipVersion) {
    return sourceWithTuple(tuple, membershipVersion, "1", "1", false);
  }

  private GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource sourceWithTuple(
      GatewayFirstPartyConnectContextEnvelope.AuthorityTuple tuple,
      String replayFence,
      String pointerVersion,
      String catalogRevision,
      boolean privateTarget) {
    return new GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource(
        ACCOUNT_ID,
        TENANT_ID,
        REALM_ID,
        "world",
        "realm",
        NAMESPACE_ID,
        privateTarget ? "ISOLATED" : "SHARED",
        GAME_INSTANCE_ID,
        pointerVersion,
        catalogRevision,
        "scope-1",
        "request-operation-17",
        tuple,
        Map.of(TENANT_ID.toString(), "9223372036854775807"),
        "65aabb12c46bf1985dd4d578f20e2d4ce129880acf6f4d70b146ccf795fb0d6c",
        replayFence,
        privateTarget ? LIFECYCLE_ID.toString() : null,
        privateTarget ? "9" : null,
        NOW_MILLIS / 1_000L + 30L);
  }

  private GatewayFirstPartyConnectContextEnvelope.AuthorityTuple authorityTuple(
      String issuerGeneration,
      String accountGeneration,
      String tenantGeneration,
      String memberGeneration,
      boolean privateTarget) {
    return new GatewayFirstPartyConnectContextEnvelope.AuthorityTuple(
        issuerGeneration,
        accountGeneration,
        Map.of(TENANT_ID.toString(), tenantGeneration),
        Map.of(TENANT_ID.toString(), memberGeneration),
        privateTarget
            ? List.of(
                new GatewayFirstPartyConnectContextEnvelope.PrivateRealmGrantVersion(
                    TENANT_ID.toString(),
                    "world",
                    "realm",
                    LIFECYCLE_ID.toString(),
                    "9223372036854775807"))
            : List.of(),
        "1".equals(accountGeneration)
            ? null
            : new GatewayFirstPartyConnectContextEnvelope.AccountSecurityCutoff(
                accountGeneration, "account:auth-authority:v1:account/" + ACCOUNT_ID, "1"),
        Map.of());
  }

  private GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata metadata() {
    return new GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata(
        "203.0.113.8", "webflux-request-17", NOW_MILLIS, NOW_MILLIS, NOW_MILLIS + 30_000L);
  }

  private Map<String, Object> payloadMap(
      GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource source) {
    String compact = GatewayFirstPartyConnectContextEnvelope.sign(source, metadata(), signingKey);
    return payload(compact);
  }

  private Map<String, Object> authorityTupleMap(
      GatewayFirstPartyConnectContextEnvelope.AuthorityTuple tuple) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("issuerAuthGeneration", tuple.issuerAuthGeneration());
    map.put("accountAuthorityGeneration", tuple.accountAuthorityGeneration());
    map.put("tenantAuthorityGeneration", tuple.tenantAuthorityGeneration());
    map.put("membershipAuthorityGeneration", tuple.membershipAuthorityGeneration());
    map.put(
        "privateRealmGrantVersions",
        tuple.privateRealmGrantVersions().stream()
            .map(
                grant ->
                    Map.of(
                        "tenantId", grant.tenantId(),
                        "worldSlug", grant.worldSlug(),
                        "realmSlug", grant.realmSlug(),
                        "playtestLifecycleId", grant.playtestLifecycleId(),
                        "grantVersion", grant.grantVersion()))
            .toList());
    if (tuple.accountSecurityCutoff() != null) {
      map.put(
          "accountSecurityCutoff",
          Map.of(
              "accountAuthorityGeneration",
                  tuple.accountSecurityCutoff().accountAuthorityGeneration(),
              "outboxStreamKey", tuple.accountSecurityCutoff().outboxStreamKey(),
              "outboxSequence", tuple.accountSecurityCutoff().outboxSequence()));
    }
    if (!tuple.tenantBillingCutoff().isEmpty()) {
      Map<String, Object> cutoffs = new LinkedHashMap<>();
      tuple
          .tenantBillingCutoff()
          .forEach(
              (tenant, cutoff) ->
                  cutoffs.put(
                      tenant,
                      Map.of(
                          "tenantAuthorityGeneration", cutoff.tenantAuthorityGeneration(),
                          "tenantBillingSequence", cutoff.tenantBillingSequence(),
                          "outboxStreamKey", cutoff.outboxStreamKey(),
                          "outboxSequence", cutoff.outboxSequence())));
      map.put("tenantBillingCutoff", cutoffs);
    }
    return map;
  }

  private Map<String, Object> payload(String compact) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(compact.split("\\.")[1]);
      return JSON.readValue(new String(bytes, StandardCharsets.UTF_8), OBJECT);
    } catch (JacksonException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private String token(Map<String, Object> payload) {
    return token(
        payload,
        Map.of(
            "alg",
            "RS256",
            "kid",
            KEY_ID,
            "typ",
            GatewayFirstPartyConnectContextEnvelope.PROTECTED_TYPE));
  }

  private String token(Map<String, Object> payload, Map<String, Object> header) {
    return compactRaw(canonical(header), canonical(payload));
  }

  private String compactRaw(String headerJson, String payloadJson) {
    try {
      String input =
          encode(headerJson.getBytes(StandardCharsets.UTF_8))
              + "."
              + encode(payloadJson.getBytes(StandardCharsets.UTF_8));
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(keyPair.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + encode(signer.sign());
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private String canonical(Object value) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void assertRejected(
      String token, GatewayFirstPartyConnectContextEnvelope.FailureReason reason) {
    assertThatThrownBy(
            () -> GatewayFirstPartyConnectContextEnvelope.verify(token, keySource, NOW_MILLIS, 0))
        .isInstanceOf(GatewayFirstPartyConnectContextEnvelope.VerificationException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((GatewayFirstPartyConnectContextEnvelope.VerificationException) failure)
                            .reason())
                    .isEqualTo(reason));
  }

  private void assertRejectedAt(
      String token,
      long nowMillis,
      int skew,
      GatewayFirstPartyConnectContextEnvelope.FailureReason reason) {
    assertThatThrownBy(
            () -> GatewayFirstPartyConnectContextEnvelope.verify(token, keySource, nowMillis, skew))
        .isInstanceOf(GatewayFirstPartyConnectContextEnvelope.VerificationException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((GatewayFirstPartyConnectContextEnvelope.VerificationException) failure)
                            .reason())
                    .isEqualTo(reason));
  }
}
