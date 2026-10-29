package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CanonicalGameplayOriginalNewAuthenticatedSessionTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);
  private static final UUID ACCOUNT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID CHARACTER_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID GAME_INSTANCE_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID SESSION_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID REGION_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REALM_ID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID VERSION_ID = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID REQUEST_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID LEASE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID BINDING_DECISION_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID RESERVATION_ID = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID RESERVATION_FENCE = uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID ACCOUNT_INDEX_FENCE = uuid("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID COVERAGE_OPERATION_ID = uuid("10101010-1010-4010-8010-101010101010");
  private static final UUID LOGIN_JTI = uuid("12121212-1212-4212-8212-121212121212");
  private static final UUID LINEAGE_ID = uuid("13131313-1313-4313-8313-131313131313");
  private static final UUID BOUND_JTI = uuid("14141414-1414-4414-8414-141414141414");
  private static final UUID OTHER_ID = uuid("15151515-1515-4515-8515-151515151515");
  private static final String NAMESPACE = "test";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String HANDLE_HEX =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final byte[] HANDLE = HexFormat.of().parseHex(HANDLE_HEX);
  private static final String ONE = "1";
  private static final String TOKEN_FENCE = "7";
  private static final long NOW_SECONDS = CLOCK.instant().getEpochSecond();
  private static final long SESSION_DURATION_MILLIS = 120_000L;
  private static KeyPair ACCOUNT_SIGNING_KEYS;

  @BeforeAll
  static void generateControlledAccountSigningKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    ACCOUNT_SIGNING_KEYS = generator.generateKeyPair();
  }

  @Test
  void buildsNonPersistableTemplateAndAcceptsExactFinalCasReadback() throws Exception {
    try (Fixture fixture = fixture(claims -> {});
        var template = template(fixture)) {
      byte[] templateBytes = template.finalCasTemplateBytes();
      String templateJson = new String(templateBytes, StandardCharsets.UTF_8);
      assertThat(templateJson)
          .contains("__ORIGINAL_NEW_ADMISSION_AT_MS__")
          .contains("__ORIGINAL_NEW_CONTINUITY_EXPIRES_AT_MS__")
          .doesNotContain(fixture.compactJwt)
          .doesNotContain("\"lineageId\"")
          .doesNotContain("\"authTokenJti\"");
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewAuthenticatedSession.parseFinalized(
                      templateBytes, CLOCK))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Canonical ORIGINAL_NEW authenticated session is invalid");

      long admissionAt = CLOCK.millis() + 100L;
      byte[] finalBytes = finalizeTemplate(templateBytes, admissionAt, SESSION_DURATION_MILLIS);
      try (var finalized = template.requireFinalizedReadback(finalBytes)) {
        assertThat(finalized.originalCandidate().bindingDecisionId())
            .isEqualTo(BINDING_DECISION_ID);
        assertThat(finalized.admissionAtMillis()).isEqualTo(admissionAt);
        assertThat(finalized.continuityBindingExpiresAtMillis())
            .isEqualTo(admissionAt + SESSION_DURATION_MILLIS);
        assertThat(finalized.authTokenHash()).isEqualTo(hash(fixture.compactJwt));
        assertThat(finalized.authTokenIssuedAt()).isEqualTo(NOW_SECONDS);
        assertThat(finalized.authTokenExpiresAt()).isEqualTo(NOW_SECONDS + 240L);
        assertThat(finalized.tokenGeneration()).isEqualTo("1");
        assertThat(finalized.issuanceFence()).isEqualTo(TOKEN_FENCE);
        assertThat(finalized.canonicalSessionDurationMillis()).isEqualTo(SESSION_DURATION_MILLIS);
        byte[] returnedHandle = finalized.opaqueRebindHandle();
        try {
          assertThat(returnedHandle).containsExactly(HANDLE);
        } finally {
          Arrays.fill(returnedHandle, (byte) 0);
        }
        byte[] exactBytes = finalized.canonicalBytes();
        try {
          assertThat(exactBytes).containsExactly(finalBytes);
        } finally {
          Arrays.fill(exactBytes, (byte) 0);
        }
        assertThat(finalized.toString()).doesNotContain(HANDLE_HEX, fixture.compactJwt);
      } finally {
        Arrays.fill(templateBytes, (byte) 0);
        Arrays.fill(finalBytes, (byte) 0);
      }
    }
  }

  @Test
  void accountSignatureAndBoundClaimsMustMatchTheOriginalLeaseAndCredential() throws Exception {
    try (Fixture valid = fixture(claims -> {})) {
      try (var template = template(valid)) {
        assertThat(template.toString()).doesNotContain(valid.compactJwt, HANDLE_HEX);
      }
    }

    try (Fixture unscoped =
        fixture(
            claims -> {
              claims.remove("tenantId");
              claims.put("authorityTuple", unscopedTuple());
              claims.put("membershipVersion", Map.of());
            })) {
      assertVerifierDenies(unscoped);
    }

    try (Fixture mismatchedTenant =
        fixture(claims -> claims.put("tenantId", OTHER_ID.toString()))) {
      assertVerifierDenies(mismatchedTenant);
    }

    try (Fixture roles = fixture(claims -> claims.put("globalRoles", List.of()))) {
      assertVerifierDenies(roles);
    }

    try (Fixture expired =
        fixture(
            claims -> {
              claims.put("exp", NOW_SECONDS - 1L);
              claims.put("nbf", NOW_SECONDS - 5L);
            })) {
      assertVerifierDenies(expired);
    }

    try (Fixture futureIat =
        fixture(
            claims -> {
              claims.put("iat", NOW_SECONDS + 6L);
              claims.put("nbf", NOW_SECONDS + 6L);
              claims.put("exp", NOW_SECONDS + 100L);
            })) {
      assertVerifierDenies(futureIat);
    }

    try (Fixture wrongJti = fixture(claims -> claims.put("jti", OTHER_ID.toString()))) {
      assertVerifierDenies(wrongJti);
    }

    KeyPairGenerator wrongGenerator = KeyPairGenerator.getInstance("RSA");
    wrongGenerator.initialize(2048);
    try (Fixture wrongSignature = fixture(claims -> {}, wrongGenerator.generateKeyPair())) {
      assertVerifierDenies(wrongSignature);
    }

    try (Fixture valid = fixture(claims -> {});
        var wrongHash =
            AccountGameplayAdmissionLeaseGrpcCodec.originalNewCredential(
                valid.compactJwt,
                HANDLE,
                LINEAGE_ID,
                BOUND_JTI,
                "f".repeat(64),
                Long.parseLong(TOKEN_FENCE),
                LEASE_ID,
                1L,
                "42",
                hash(
                    canonical(
                        valid.candidate.leaseEvidence().carrier().get("outboxCheckpoints"))))) {
      assertThatThrownBy(() -> valid.verifier.verify(valid.candidate, wrongHash))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Original NEW bound credential is invalid");
    }

    try (Fixture wrongFence = fixture(claims -> claims.put("issuanceFence", "8"))) {
      assertVerifierDenies(wrongFence);
    }

    try (Fixture wrongMembership =
        fixture(claims -> claims.put("membershipVersion", Map.of(TENANT_ID.toString(), "10")))) {
      assertVerifierDenies(wrongMembership);
    }

    try (Fixture wrongTuple =
        fixture(
            claims ->
                claims.put(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration",
                        "1",
                        "accountAuthorityGeneration",
                        "1",
                        "tenantAuthorityGeneration",
                        Map.of(TENANT_ID.toString(), "3"),
                        "membershipAuthorityGeneration",
                        Map.of(TENANT_ID.toString(), "3"),
                        "privateRealmGrantVersions",
                        List.of())))) {
      assertVerifierDenies(wrongTuple);
    }
  }

  @Test
  void strictFinalReaderRejectsUnknownDuplicateTrailingMalformedAndOversizeJson() throws Exception {
    try (Fixture fixture = fixture(claims -> {});
        var template = template(fixture)) {
      byte[] candidate = template.finalCasTemplateBytes();
      byte[] complete = finalizeTemplate(candidate, CLOCK.millis() + 100L, SESSION_DURATION_MILLIS);
      try {
        assertDenied(addRootField(complete, "lineageId", "not-persisted"));
        String duplicate =
            new String(complete, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"schemaVersion\":2,");
        assertDenied(duplicate.getBytes(StandardCharsets.UTF_8));
        byte[] trailing = Arrays.copyOf(complete, complete.length + 3);
        trailing[complete.length] = ' ';
        trailing[complete.length + 1] = '{';
        trailing[complete.length + 2] = '}';
        assertDenied(trailing);
        assertDenied(new byte[] {(byte) 0xc3, (byte) 0x28});
        assertDenied(
            new byte[CanonicalGameplayOriginalNewAuthenticatedSession.MAX_SESSION_BYTES + 1]);
      } finally {
        Arrays.fill(candidate, (byte) 0);
        Arrays.fill(complete, (byte) 0);
      }
    }
  }

  @Test
  void strictFinalReaderRejectsMissingNullPlaceholderAndChangedAnchors() throws Exception {
    try (Fixture fixture = fixture(claims -> {});
        var template = template(fixture)) {
      byte[] templateBytes = template.finalCasTemplateBytes();
      byte[] complete =
          finalizeTemplate(templateBytes, CLOCK.millis() + 100L, SESSION_DURATION_MILLIS);
      try {
        assertDenied(mutate(complete, root -> root.remove("rebindHandle")));
        assertDenied(mutate(complete, root -> root.putNull("membershipBaseline")));
        assertDenied(mutate(complete, root -> root.put("bindingGeneration", "2")));
        assertDenied(mutate(complete, root -> root.put("schemaVersion", 4294967298L)));
        assertDenied(mutate(complete, root -> root.put("issuanceFence", "8")));
        assertDenied(mutate(complete, root -> root.put("tokenGeneration", "2")));
        assertDenied(mutate(complete, root -> root.put("continuityBindingExpiresAt", "1")));
        assertDenied(mutate(complete, root -> root.put("admissionAt", "9223372036854775807")));
        assertDenied(
            mutate(
                complete,
                root -> ((ObjectNode) root.get("originalCandidate")).put("unexpected", "field")));
        assertDenied(
            mutate(
                complete,
                root ->
                    ((ObjectNode) root.get("membershipBaseline"))
                        .put("membershipAuthorityGeneration", "8")));
        assertDenied(
            mutate(
                complete,
                root ->
                    ((ObjectNode) root.get("authorityTuple"))
                        .put("accountAuthorityGeneration", "2")));
        assertDenied(
            mutate(
                complete, root -> ((ArrayNode) root.get("outboxCheckpoints")).add("unexpected")));
      } finally {
        Arrays.fill(templateBytes, (byte) 0);
        Arrays.fill(complete, (byte) 0);
      }
    }
  }

  @Test
  void finalCasReadbackAllowsOnlyAdmissionAndContinuityTimeSlotsToChange() throws Exception {
    try (Fixture fixture = fixture(claims -> {});
        var template = template(fixture)) {
      byte[] templateBytes = template.finalCasTemplateBytes();
      byte[] complete =
          finalizeTemplate(templateBytes, CLOCK.millis() + 100L, SESSION_DURATION_MILLIS);
      byte[] changed =
          mutate(
              complete,
              root -> {
                long admissionAt = Long.parseLong(root.get("admissionAt").textValue());
                root.put("canonicalSessionDurationMillis", "119999");
                root.put("continuityBindingExpiresAt", Long.toString(admissionAt + 119_999L));
              });
      try {
        assertThatThrownBy(() -> template.requireFinalizedReadback(changed))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Canonical ORIGINAL_NEW authenticated session is invalid");
      } finally {
        Arrays.fill(templateBytes, (byte) 0);
        Arrays.fill(complete, (byte) 0);
        Arrays.fill(changed, (byte) 0);
      }
    }
  }

  private static void assertVerifierDenies(Fixture fixture) {
    assertThatThrownBy(() -> template(fixture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Original NEW bound credential is invalid");
  }

  private static CanonicalGameplayOriginalNewAuthenticatedSession.Template template(
      Fixture fixture) {
    return CanonicalGameplayOriginalNewAuthenticatedSession.template(
        fixture.candidate, fixture.credential, fixture.verifier, SESSION_DURATION_MILLIS);
  }

  private static void assertDenied(byte[] bytes) {
    try {
      assertThatThrownBy(
              () -> CanonicalGameplayOriginalNewAuthenticatedSession.parseFinalized(bytes, CLOCK))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Canonical ORIGINAL_NEW authenticated session is invalid");
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static byte[] finalizeTemplate(byte[] template, long admissionAt, long duration)
      throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(template);
    root.put("admissionAt", Long.toString(admissionAt));
    root.put("continuityBindingExpiresAt", Long.toString(Math.addExact(admissionAt, duration)));
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
  }

  private static byte[] mutate(byte[] input, java.util.function.Consumer<ObjectNode> mutation)
      throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(input);
    mutation.accept(root);
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
  }

  private static byte[] addRootField(byte[] input, String name, String value) throws Exception {
    return mutate(input, root -> root.put(name, value));
  }

  private static Fixture fixture(java.util.function.Consumer<Map<String, Object>> mutateClaims)
      throws Exception {
    return fixture(mutateClaims, ACCOUNT_SIGNING_KEYS);
  }

  private static Fixture fixture(
      java.util.function.Consumer<Map<String, Object>> mutateClaims, KeyPair signingKey)
      throws Exception {
    AccountGameplayAdmissionLeaseEvidence lease = lease();
    CanonicalGameplayOriginalNewCandidate candidate = candidate(lease);
    Map<String, Object> claims = boundClaims(lease);
    mutateClaims.accept(claims);
    String compactJwt = sign(claims, signingKey);
    String tokenHash = hash(compactJwt);
    String checkpointsSha256 = hash(canonical(lease.carrier().get("outboxCheckpoints")));
    AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential credential =
        AccountGameplayAdmissionLeaseGrpcCodec.originalNewCredential(
            compactJwt,
            HANDLE,
            LINEAGE_ID,
            BOUND_JTI,
            tokenHash,
            Long.parseLong(TOKEN_FENCE),
            LEASE_ID,
            1L,
            "42",
            checkpointsSha256);
    return new Fixture(candidate, credential, compactJwt, verifier());
  }

  private static CanonicalGameplayOriginalNewCandidate candidate(
      AccountGameplayAdmissionLeaseEvidence lease) {
    CanonicalGameplayBindingIdentity binding =
        new CanonicalGameplayBindingIdentity(
            ACCOUNT_ID,
            TENANT_ID,
            PLAYABLE_NAMESPACE_ID,
            CHARACTER_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            77L,
            SESSION_ID.toString(),
            REGION_ID,
            BigInteger.ONE,
            GameSessionAccountDelegationProfile.ISSUER,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE);
    CanonicalPlayableTarget target =
        new CanonicalPlayableTarget(
            NAMESPACE,
            "tenant",
            TENANT_ID,
            "world",
            "World",
            REALM_ID,
            "realm",
            "Realm",
            42L,
            77L,
            PLAYABLE_NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            VERSION_ID,
            3L,
            5L,
            6L,
            "a".repeat(64),
            1L,
            REQUEST_ID.toString(),
            "b".repeat(64),
            OriginKind.NO_PRIOR_POINTER,
            null,
            OTHER_ID,
            RESERVATION_FENCE,
            "sha256:" + "c".repeat(64),
            100L,
            "sha256:" + "d".repeat(64),
            Outcome.COMMITTED,
            false,
            Instant.parse("2026-10-26T00:00:00Z"),
            "PRESEEDED_ONLY");
    var bindingRef = binding.bindingRef();
    var transition =
        new CanonicalGameplayOriginalNewCandidate.TransitionSource(
            BINDING_DECISION_ID,
            bindingRef,
            BigInteger.ONE,
            RESERVATION_ID,
            RESERVATION_FENCE,
            ACCOUNT_INDEX_FENCE);
    var reservation =
        new CanonicalGameplayOriginalNewCandidate.ReservationSource(
            RESERVATION_ID,
            bindingRef,
            BigInteger.ONE,
            BINDING_DECISION_ID,
            BigInteger.ZERO,
            RESERVATION_FENCE,
            COVERAGE_OPERATION_ID,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE);
    return new CanonicalGameplayOriginalNewCandidate(
        BINDING_DECISION_ID, lease, binding, target, transition, reservation);
  }

  private static AccountGameplayAdmissionLeaseEvidence lease() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", NAMESPACE);
    value.put("callerWorkload", GAME_SESSION_PEER);
    value.put("requestId", REQUEST_ID.toString());
    value.put("leaseId", LEASE_ID.toString());
    value.put("leaseFence", ONE);
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", ACCOUNT_ID.toString());
    scope.put("tenantId", TENANT_ID.toString());
    scope.put("realmId", REALM_ID.toString());
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateNamespaceId", PLAYABLE_NAMESPACE_ID.toString());
    scope.put("playableStateScope", "SHARED");
    scope.put("gameInstanceId", GAME_INSTANCE_ID.toString());
    scope.put("characterId", CHARACTER_ID.toString());
    scope.put("sessionId", SESSION_ID.toString());
    scope.put("bindingGeneration", ONE);
    scope.put("catalogRevision", "5");
    scope.put("pointerVersion", "6");
    scope.put("regionId", REGION_ID.toString());
    scope.put("regionEpoch", ONE);
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", ONE,
            "accountAuthorityGeneration", ONE,
            "tenantAuthorityGeneration", Map.of(TENANT_ID.toString(), "2"),
            "membershipAuthorityGeneration", Map.of(TENANT_ID.toString(), "3"),
            "privateRealmGrantVersions", List.of()));
    value.put("issuanceFence", TOKEN_FENCE);
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT_ID.toString(), "9"),
            "membershipAuthorityGeneration",
            "3"));
    value.put("outboxCheckpoints", checkpoints());
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", ACCOUNT_ID.toString()),
            Map.entry("operationId", OTHER_ID.toString()),
            Map.entry("issuanceRequestId", REQUEST_ID.toString()),
            Map.entry("tokenJti", LOGIN_JTI.toString()),
            Map.entry("tokenSHA256", "e".repeat(64)),
            Map.entry("tokenGeneration", ONE),
            Map.entry("issuanceFence", TOKEN_FENCE),
            Map.entry("tokenIdentityFence", ONE),
            Map.entry("tokenProfile", GameSessionAccountDelegationProfile.PROFILE),
            Map.entry("issuedAt", Long.toString(NOW_SECONDS - 1)),
            Map.entry("notBefore", Long.toString(NOW_SECONDS - 1)),
            Map.entry("expiresAt", Long.toString(NOW_SECONDS + 299))));
    value.put("evaluatedAt", Long.toString(CLOCK.millis()));
    value.put("expiresAt", Long.toString(CLOCK.millis() + 15_000L));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  private static List<Map<String, Object>> checkpoints() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (String suffix :
        List.of(
            "account/" + ACCOUNT_ID,
            "issuer/" + GameSessionAccountDelegationProfile.ISSUER,
            "membership/" + ACCOUNT_ID + "/" + TENANT_ID,
            "tenant/" + TENANT_ID)) {
      result.add(
          Map.of(
              "outboxStreamKey",
              "account:auth-authority:v1:" + suffix,
              "outboxSequence",
              suffix.startsWith("membership/") || suffix.startsWith("tenant/") ? "9" : "0"));
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> boundClaims(AccountGameplayAdmissionLeaseEvidence lease) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", GameSessionAccountDelegationProfile.ISSUER);
    claims.put("sub", ACCOUNT_ID.toString());
    claims.put("jti", BOUND_JTI.toString());
    claims.put("accountId", ACCOUNT_ID.toString());
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
    claims.put("iat", NOW_SECONDS);
    claims.put("nbf", NOW_SECONDS);
    claims.put("exp", NOW_SECONDS + 240L);
    claims.put("tokenGeneration", ONE);
    claims.put("authorityTuple", lease.carrier().get("authorityTuple"));
    claims.put(
        "membershipVersion",
        ((Map<?, ?>) lease.carrier().get("membershipBaseline")).get("membershipVersion"));
    claims.put("issuanceFence", TOKEN_FENCE);
    claims.put("tenantId", TENANT_ID.toString());
    return claims;
  }

  private static Map<String, Object> unscopedTuple() {
    return Map.of(
        "issuerAuthGeneration", ONE,
        "accountAuthorityGeneration", ONE,
        "tenantAuthorityGeneration", Map.of(),
        "membershipAuthorityGeneration", Map.of(),
        "privateRealmGrantVersions", List.of());
  }

  private static String sign(Map<String, Object> claims, KeyPair keyPair) throws Exception {
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"original-new-test\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims));
    String signingInput = header + "." + payload;
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keyPair.getPrivate());
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
  }

  private static CanonicalGameplayOriginalNewBoundCredentialVerifier verifier() throws Exception {
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
    var publicKey = (java.security.interfaces.RSAPublicKey) ACCOUNT_SIGNING_KEYS.getPublic();
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
                        "original-new-test",
                        "n",
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(unsigned(publicKey.getModulus().toByteArray())),
                        "e",
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                unsigned(publicKey.getPublicExponent().toByteArray()))))));
    var cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    pin, jwks.getBytes(StandardCharsets.UTF_8)),
            pin,
            CLOCK,
            Duration.ofSeconds(30));
    return new CanonicalGameplayOriginalNewBoundCredentialVerifier(cache, CLOCK);
  }

  private static String canonical(Object value) throws Exception {
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
        StandardCharsets.UTF_8);
  }

  private static String hash(String value) throws Exception {
    return hash(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static String hash(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static byte[] unsigned(byte[] bytes) {
    return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Fixture implements AutoCloseable {
    private final CanonicalGameplayOriginalNewCandidate candidate;
    private final AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential credential;
    private final String compactJwt;
    private final CanonicalGameplayOriginalNewBoundCredentialVerifier verifier;

    private Fixture(
        CanonicalGameplayOriginalNewCandidate candidate,
        AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential credential,
        String compactJwt,
        CanonicalGameplayOriginalNewBoundCredentialVerifier verifier) {
      this.candidate = candidate;
      this.credential = credential;
      this.compactJwt = compactJwt;
      this.verifier = verifier;
    }

    @Override
    public void close() {
      credential.close();
    }
  }
}
