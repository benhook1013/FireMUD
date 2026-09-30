package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.GatewayConnectContext;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.GatewayConnectContextSignature;
import net.firedevops.firemud.test.SelectedTargetConnectContextTestVectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayConnectContextCodecTest {
  private static final String KID = SelectedTargetConnectContextTestVectors.GATEWAY_KID;
  private static final Instant NOW =
      Instant.ofEpochSecond(SelectedTargetConnectContextTestVectors.NOW_EPOCH_SECONDS);

  private final ObjectMapper json = new ObjectMapper();
  private KeyPair keyPair;
  private Clock clock;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    keyPair = generator.generateKeyPair();
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void verifiesRealEd25519ContextAndPreservesExactEnvelopeAndEveryAcceptedField() throws Exception {
    byte[] payload = json.writeValueAsBytes(validContext());
    String envelope = GatewayConnectContextSignature.sign(payload, KID, keyPair.getPrivate());

    GatewayConnectContext context = verify(envelope);

    assertEquals(envelope, context.signedEnvelope());
    assertArrayEquals(payload, context.signedPayload());
    Map<String, Object> source = SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    assertEquals("game-session", context.text("audience"));
    assertEquals("game-session-service", context.text("recipient"));
    assertEquals(source.get("tenantId"), context.text("tenantId"));
    assertEquals(source.get("requestId"), context.text("connectRequestId"));
    assertEquals(source.get("jti"), context.text("connectTokenJti"));
    assertEquals(source.get("iat"), context.integer("issuedAt"));
    long sourceDeadline =
        GatewayConnectContextCodec.requireGatewayDeadline(
            ((BigInteger) source.get("iat")).longValueExact(),
            ((BigInteger) source.get("exp")).longValueExact(),
            context.integer("verifiedAt").longValueExact());
    assertEquals(SelectedTargetConnectContextTestVectors.SOURCE_EXPIRES_AT, sourceDeadline);
    assertEquals(BigInteger.valueOf(sourceDeadline), context.integer("expiresAt"));
    assertTrue(context.integer("expiresAt").longValueExact() <= sourceDeadline);
    assertEquals(
        SelectedTargetConnectContextTestVectors.LARGE_COUNTER,
        context.integer("replayAdmissionFence"));
    assertEquals(
        SelectedTargetConnectContextTestVectors.LARGE_COUNTER,
        nested(
            context.claims(),
            "authorityTuple",
            "membershipAuthorityGeneration",
            SelectedTargetConnectContextTestVectors.TENANT_ID));
    assertEquals(validContext().keySet(), context.claims().keySet());
    assertThrows(
        UnsupportedOperationException.class, () -> context.claims().put("audience", "other"));
  }

  @Test
  void rejectsDuplicateKeysUnknownContextFieldsAndTrailingJsonAfterSignatureVerification()
      throws Exception {
    String validJson = json.writeValueAsString(validContext());
    String duplicateAudience =
        validJson.replace(
            "\"audience\":\"game-session\"",
            "\"audience\":\"game-session\",\"audience\":\"game-session\"");
    String duplicateKeyEnvelope =
        GatewayConnectContextSignature.sign(
            duplicateAudience.getBytes(StandardCharsets.UTF_8), KID, keyPair.getPrivate());
    assertThrows(IllegalArgumentException.class, () -> verify(duplicateKeyEnvelope));

    Map<String, Object> unknownField = validContext();
    unknownField.put("unregisteredContextField", "not-accepted");
    assertRejected(unknownField);

    String trailingJsonEnvelope =
        GatewayConnectContextSignature.sign(
            (validJson + " {} ").getBytes(StandardCharsets.UTF_8), KID, keyPair.getPrivate());
    assertThrows(IllegalArgumentException.class, () -> verify(trailingJsonEnvelope));
  }

  @Test
  void returnedAuthorityClaimsAreRecursivelyImmutableAndPayloadBytesAreDefensiveCopies()
      throws Exception {
    byte[] originalPayload = json.writeValueAsBytes(validContext());
    String envelope =
        GatewayConnectContextSignature.sign(originalPayload, KID, keyPair.getPrivate());
    GatewayConnectContext context = verify(envelope);

    byte[] exposedPayload = context.signedPayload();
    exposedPayload[0] ^= 1;
    assertArrayEquals(originalPayload, context.signedPayload());
    assertEquals(envelope, context.signedEnvelope());

    Map<String, Object> claims = context.claims();
    @SuppressWarnings("unchecked")
    Map<String, Object> authorityTuple = (Map<String, Object>) claims.get("authorityTuple");
    assertThrows(
        UnsupportedOperationException.class,
        () -> authorityTuple.put("accountAuthorityGeneration", BigInteger.ZERO));
    @SuppressWarnings("unchecked")
    Map<String, Object> tenantGenerations =
        (Map<String, Object>) authorityTuple.get("tenantAuthorityGeneration");
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            tenantGenerations.put(
                SelectedTargetConnectContextTestVectors.TENANT_ID, BigInteger.ZERO));
    assertEquals(
        SelectedTargetConnectContextTestVectors.LARGE_COUNTER,
        nested(
            context.claims(),
            "authorityTuple",
            "membershipAuthorityGeneration",
            SelectedTargetConnectContextTestVectors.TENANT_ID));
    assertArrayEquals(originalPayload, context.signedPayload());

    Map<String, Object> privateClaims =
        projectContext(SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims());
    GatewayConnectContext privateContext = verify(sign(privateClaims));
    @SuppressWarnings("unchecked")
    Map<String, Object> privateAuthorityTuple =
        (Map<String, Object>) privateContext.claims().get("authorityTuple");
    @SuppressWarnings("unchecked")
    List<Object> grants = (List<Object>) privateAuthorityTuple.get("privateRealmGrantVersions");
    assertThrows(UnsupportedOperationException.class, () -> grants.add(Map.of()));
    @SuppressWarnings("unchecked")
    Map<String, Object> grant = (Map<String, Object>) grants.getFirst();
    assertThrows(
        UnsupportedOperationException.class, () -> grant.put("grantVersion", BigInteger.ZERO));
    assertEquals(privateClaims, privateContext.claims());
  }

  @Test
  void rejectsMissingOrChangedTargetAndWrongReceiverBindings() throws Exception {
    Map<String, Object> missingTarget = validContext();
    missingTarget.remove("realmId");
    assertRejected(missingTarget);

    Map<String, Object> changedTarget = validContext();
    changedTarget.put("tenantId", "018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e");
    assertRejected(changedTarget);

    Map<String, Object> wrongAudience = validContext();
    wrongAudience.put("audience", "account-service");
    assertRejected(wrongAudience);

    Map<String, Object> wrongRecipient = validContext();
    wrongRecipient.put("recipient", "another-service");
    assertRejected(wrongRecipient);
  }

  @Test
  void rejectsMalformedMapsAndNonCanonicalCounters() throws Exception {
    Map<String, Object> missingMembership = validContext();
    missingMembership.put("membershipVersion", Map.of());
    assertRejected(missingMembership);

    Map<String, Object> malformedAuthority = validContext();
    @SuppressWarnings("unchecked")
    Map<String, Object> tuple =
        new LinkedHashMap<>((Map<String, Object>) malformedAuthority.get("authorityTuple"));
    tuple.put(
        "membershipAuthorityGeneration",
        Map.of(SelectedTargetConnectContextTestVectors.TENANT_ID, "7"));
    malformedAuthority.put("authorityTuple", tuple);
    assertRejected(malformedAuthority);

    Map<String, Object> fractionalCounter = validContext();
    fractionalCounter.put("replayAdmissionFence", 1.5d);
    assertRejected(fractionalCounter);

    Map<String, Object> numericLegacyIdentity = validContext();
    numericLegacyIdentity.put("accountId", 42);
    assertRejected(numericLegacyIdentity);

    Map<String, Object> stringLegacyInstanceId = validContext();
    stringLegacyInstanceId.put("gameInstanceId", "9");
    assertRejected(stringLegacyInstanceId);

    Map<String, Object> opaqueLegacyNamespace = validContext();
    opaqueLegacyNamespace.put("playableStateNamespaceId", "namespace-9");
    assertRejected(opaqueLegacyNamespace);
  }

  @Test
  void validatesConditionalNonPublicLifecycleAndExactGrantShape() throws Exception {
    Map<String, Object> privateContext =
        projectContext(SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims());
    GatewayConnectContext verified = verify(sign(privateContext));
    assertEquals("preview", verified.text("realmSlug"));
    assertEquals("018f8f0a-7ac1-7f79-be6a-bf4a312c0d8e", verified.text("playtestLifecycleId"));

    Map<String, Object> missingGeneration =
        projectContext(SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims());
    missingGeneration.remove("playtestStateGeneration");
    assertRejected(missingGeneration);

    Map<String, Object> wrongGrantLifecycle =
        projectContext(SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims());
    @SuppressWarnings("unchecked")
    Map<String, Object> tuple =
        new LinkedHashMap<>((Map<String, Object>) wrongGrantLifecycle.get("authorityTuple"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> grants =
        (List<Map<String, Object>>) tuple.get("privateRealmGrantVersions");
    Map<String, Object> grant = new LinkedHashMap<>(grants.getFirst());
    grant.put("playtestLifecycleId", "018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e");
    tuple.put("privateRealmGrantVersions", List.of(grant));
    wrongGrantLifecycle.put("authorityTuple", tuple);
    assertRejected(wrongGrantLifecycle);
  }

  @Test
  void acceptsZeroTenantBillingBaselineButRequiresPositiveOutboxSequence() throws Exception {
    Map<String, Object> withBillingCutoff = validContext();
    @SuppressWarnings("unchecked")
    Map<String, Object> tuple =
        new LinkedHashMap<>((Map<String, Object>) withBillingCutoff.get("authorityTuple"));
    tuple.put(
        "tenantBillingCutoff",
        Map.of(
            SelectedTargetConnectContextTestVectors.TENANT_ID,
            Map.of(
                "tenantAuthorityGeneration",
                BigInteger.valueOf(13),
                "tenantBillingSequence",
                BigInteger.ZERO,
                "outboxStreamKey",
                "account:auth-authority:v1:tenant/"
                    + SelectedTargetConnectContextTestVectors.TENANT_ID,
                "outboxSequence",
                BigInteger.ONE)));
    withBillingCutoff.put("authorityTuple", tuple);
    assertEquals("production", verify(sign(withBillingCutoff)).text("realmSlug"));

    Map<String, Object> zeroOutboxSequence = validContext();
    @SuppressWarnings("unchecked")
    Map<String, Object> badTuple =
        new LinkedHashMap<>((Map<String, Object>) zeroOutboxSequence.get("authorityTuple"));
    badTuple.put(
        "tenantBillingCutoff",
        Map.of(
            SelectedTargetConnectContextTestVectors.TENANT_ID,
            Map.of(
                "tenantAuthorityGeneration",
                BigInteger.valueOf(13),
                "tenantBillingSequence",
                BigInteger.ZERO,
                "outboxStreamKey",
                "account:auth-authority:v1:tenant/"
                    + SelectedTargetConnectContextTestVectors.TENANT_ID,
                "outboxSequence",
                BigInteger.ZERO)));
    zeroOutboxSequence.put("authorityTuple", badTuple);
    assertRejected(zeroOutboxSequence);
  }

  @Test
  void projectsSourceExpiryWithoutExtendingItAndDoesNotMutateVerifiedClaims() throws Exception {
    Map<String, Object> source = SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    String originalSource = json.writeValueAsString(source);
    Map<String, Object> projected = projectContext(source);
    assertEquals(originalSource, json.writeValueAsString(source));
    assertEquals(source.get("requestId"), projected.get("connectRequestId"));
    assertEquals(source.get("jti"), projected.get("connectTokenJti"));
    assertEquals(source.get("iat"), projected.get("issuedAt"));
    assertEquals(source.get("authorityTuple"), projected.get("authorityTuple"));
    assertEquals(source.get("membershipVersion"), projected.get("membershipVersion"));
    assertEquals(source.get("replayAdmissionFence"), projected.get("replayAdmissionFence"));
    assertEquals(
        SelectedTargetConnectContextTestVectors.SOURCE_EXPIRES_AT,
        ((BigInteger) projected.get("expiresAt")).longValueExact());
    assertEquals(false, projected.containsKey("requestId"));
    assertEquals(false, projected.containsKey("exp"));

    Map<String, Object> shorterSource =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    long sourceIssuedAt = SelectedTargetConnectContextTestVectors.SOURCE_ISSUED_AT;
    shorterSource.put("exp", BigInteger.valueOf(sourceIssuedAt + 10L));
    Map<String, Object> shorterContext = projectContext(shorterSource);
    assertEquals(
        sourceIssuedAt + 10L, ((BigInteger) shorterContext.get("expiresAt")).longValueExact());

    Map<String, Object> futureIssuedSource =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    long gatewayVerifiedAt = SelectedTargetConnectContextTestVectors.NOW_EPOCH_SECONDS;
    futureIssuedSource.put("iat", BigInteger.valueOf(gatewayVerifiedAt + 3L));
    futureIssuedSource.put("exp", BigInteger.valueOf(gatewayVerifiedAt + 15L));
    Map<String, Object> futureIssuedContext = projectContext(futureIssuedSource, gatewayVerifiedAt);
    assertEquals(BigInteger.valueOf(gatewayVerifiedAt + 3L), futureIssuedContext.get("issuedAt"));
    assertEquals(BigInteger.valueOf(gatewayVerifiedAt + 15L), futureIssuedContext.get("expiresAt"));
    verify(sign(futureIssuedContext));

    Map<String, Object> excessiveFutureIssuedSource =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    excessiveFutureIssuedSource.put("iat", BigInteger.valueOf(gatewayVerifiedAt + 6L));
    excessiveFutureIssuedSource.put("exp", BigInteger.valueOf(gatewayVerifiedAt + 20L));
    assertThrows(
        IllegalArgumentException.class,
        () -> projectContext(excessiveFutureIssuedSource, gatewayVerifiedAt));

    Map<String, Object> overlongSource =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    overlongSource.put("exp", BigInteger.valueOf(sourceIssuedAt + 31L));
    assertThrows(IllegalArgumentException.class, () -> projectContext(overlongSource));

    Map<String, Object> unknownSourceClaim =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    unknownSourceClaim.put("sub", SelectedTargetConnectContextTestVectors.ACCOUNT_ID);
    assertThrows(IllegalArgumentException.class, () -> projectContext(unknownSourceClaim));
  }

  @Test
  void rejectsMillisecondTimesExpiredContextAndClockSkewLifetimeExtension() throws Exception {
    Map<String, Object> milliseconds = validContext();
    milliseconds.put("issuedAt", 1_700_000_000_000L);
    milliseconds.put("verifiedAt", 1_700_000_000_000L);
    milliseconds.put("expiresAt", 1_700_000_000_030L);
    assertRejected(milliseconds);

    Map<String, Object> expired = validContext();
    expired.put("expiresAt", NOW.getEpochSecond());
    assertRejected(expired);

    Map<String, Object> extendsLifetimeWithSkew = validContext();
    extendsLifetimeWithSkew.put("expiresAt", NOW.getEpochSecond() + 31L);
    assertRejected(extendsLifetimeWithSkew);
  }

  @Test
  void rejectsUnknownGatewayKeyAndWrongSignature() throws Exception {
    String envelope = sign(validContext());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.verifyAndDecode(
                envelope, Map.of("another-key", keyPair.getPublic()), clock));

    String[] parts = envelope.split("\\.");
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 1;
    String wrongSignature =
        parts[0]
            + "."
            + parts[1]
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    assertThrows(IllegalArgumentException.class, () -> verify(wrongSignature));
  }

  @Test
  void gatewayDeadlineUsesOriginalIssuedAtAndCapsAtSourceExpiration() {
    long issuedAt = NOW.getEpochSecond() - 2;
    assertEquals(
        issuedAt + GatewayConnectContextCodec.MAX_CONTEXT_LIFETIME_SECONDS,
        GatewayConnectContextCodec.requireGatewayDeadline(
            issuedAt,
            issuedAt + GatewayConnectContextCodec.MAX_CONTEXT_LIFETIME_SECONDS,
            issuedAt + 1));
    assertEquals(
        issuedAt + 10,
        GatewayConnectContextCodec.requireGatewayDeadline(issuedAt, issuedAt + 10, issuedAt + 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.requireGatewayDeadline(
                issuedAt, issuedAt + 31, issuedAt + 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.requireGatewayDeadline(
                issuedAt, issuedAt + 10, issuedAt + 10));
  }

  private GatewayConnectContext verify(String envelope) {
    return GatewayConnectContextCodec.verifyAndDecode(
        envelope, Map.of(KID, keyPair.getPublic()), clock);
  }

  private String sign(Map<String, Object> claims) throws Exception {
    return GatewayConnectContextSignature.sign(
        json.writeValueAsBytes(claims), KID, keyPair.getPrivate());
  }

  private void assertRejected(Map<String, Object> claims) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> verify(sign(claims)));
  }

  private static BigInteger nested(
      Map<String, Object> claims, String object, String nestedObject, String key) {
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) claims.get(object);
    @SuppressWarnings("unchecked")
    Map<String, Object> second = (Map<String, Object>) first.get(nestedObject);
    return (BigInteger) second.get(key);
  }

  private static Map<String, Object> validContext() {
    return projectContext(SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims());
  }

  private static Map<String, Object> projectContext(Map<String, Object> source) {
    return projectContext(source, SelectedTargetConnectContextTestVectors.GATEWAY_VERIFIED_AT);
  }

  private static Map<String, Object> projectContext(
      Map<String, Object> source, long gatewayVerifiedAt) {
    return new LinkedHashMap<>(
        GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
            source, gatewayVerifiedAt, "gateway-request-42"));
  }
}
