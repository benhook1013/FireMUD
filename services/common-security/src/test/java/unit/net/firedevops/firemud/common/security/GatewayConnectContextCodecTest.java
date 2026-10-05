package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import net.firedevops.firemud.common.security.HistoricalGatewayConnectEvidence;
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
    assertEquals(
        SelectedTargetConnectContextTestVectors.LARGE_COUNTER.toString(),
        nestedString(
            context.claims(),
            "membershipVersion",
            SelectedTargetConnectContextTestVectors.TENANT_ID));
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

    for (Object malformedVersion :
        List.of(
            BigInteger.ONE,
            new BigDecimal("1.0"),
            new BigDecimal("1E2"),
            "0",
            "00",
            "+1",
            "-1",
            " 1",
            "1 ",
            "1e2",
            "1.0",
            Boolean.TRUE)) {
      Map<String, Object> malformedMembership = validContext();
      malformedMembership.put(
          "membershipVersion",
          Map.of(SelectedTargetConnectContextTestVectors.TENANT_ID, malformedVersion));
      assertRejected(malformedMembership);
    }

    Map<String, Object> nullMembership = validContext();
    nullMembership.put(
        "membershipVersion",
        java.util.Collections.singletonMap(
            SelectedTargetConnectContextTestVectors.TENANT_ID, null));
    assertRejected(nullMembership);

    Map<String, Object> extraMembership = validContext();
    extraMembership.put(
        "membershipVersion",
        Map.of(
            SelectedTargetConnectContextTestVectors.TENANT_ID,
            "1",
            "018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e",
            "2"));
    assertRejected(extraMembership);

    Map<String, Object> wrongMembershipTenant = validContext();
    wrongMembershipTenant.put(
        "membershipVersion", Map.of("018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e", "1"));
    assertRejected(wrongMembershipTenant);

    Map<String, Object> sourceWithNumericMembership =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    sourceWithNumericMembership.put(
        "membershipVersion",
        Map.of(
            SelectedTargetConnectContextTestVectors.TENANT_ID,
            SelectedTargetConnectContextTestVectors.LARGE_COUNTER));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
                sourceWithNumericMembership,
                SelectedTargetConnectContextTestVectors.GATEWAY_VERIFIED_AT,
                "gateway-request-42"));

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
  void matchesPublicAndPrivateSourceExactlyAndAllowsShorterGatewayExpiry() throws Exception {
    Map<String, Object> publicSource =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    String publicSourceBefore = json.writeValueAsString(publicSource);
    Map<String, Object> publicPayload = projectContext(publicSource);
    publicPayload.put(
        "expiresAt",
        BigInteger.valueOf(SelectedTargetConnectContextTestVectors.SOURCE_ISSUED_AT + 20L));
    String publicEnvelope = sign(publicPayload);
    GatewayConnectContext publicContext = verify(publicEnvelope);
    String publicClaimsBefore = json.writeValueAsString(publicContext.claims());
    byte[] publicSignedPayloadBefore = publicContext.signedPayload();

    GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesGatewayContext(
        publicSource, publicContext);

    assertEquals(publicSourceBefore, json.writeValueAsString(publicSource));
    assertEquals(publicEnvelope, publicContext.signedEnvelope());
    assertEquals(publicClaimsBefore, json.writeValueAsString(publicContext.claims()));
    assertArrayEquals(publicSignedPayloadBefore, publicContext.signedPayload());
    assertEquals(
        SelectedTargetConnectContextTestVectors.SOURCE_ISSUED_AT + 20L,
        publicContext.integer("expiresAt").longValueExact());

    Map<String, Object> privateSource =
        SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims();
    String privateSourceBefore = json.writeValueAsString(privateSource);
    Map<String, Object> privatePayload = projectContext(privateSource);
    GatewayConnectContext privateContext = verify(sign(privatePayload));

    GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesGatewayContext(
        privateSource, privateContext);

    assertEquals(privateSourceBefore, json.writeValueAsString(privateSource));
    assertEquals(privatePayload, privateContext.claims());
  }

  @Test
  void rejectsChangedAccountTargetAuthorityMapsCountersLifecycleAndMappedClaims() throws Exception {
    Map<String, Object> publicSource =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    GatewayConnectContext publicContext = verify(sign(projectContext(publicSource)));

    Map<String, Object> changedTarget =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    changedTarget.put("realmId", "018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e");
    assertSourceMismatch(changedTarget, publicContext);

    Map<String, Object> changedMembershipMap =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    changedMembershipMap.put(
        "membershipVersion",
        Map.of(
            SelectedTargetConnectContextTestVectors.TENANT_ID,
            SelectedTargetConnectContextTestVectors.LARGE_COUNTER.add(BigInteger.ONE)));
    assertSourceMismatch(changedMembershipMap, publicContext);

    Map<String, Object> changedAuthorityCounter =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    @SuppressWarnings("unchecked")
    Map<String, Object> changedAuthorityTuple =
        new LinkedHashMap<>((Map<String, Object>) changedAuthorityCounter.get("authorityTuple"));
    changedAuthorityTuple.put(
        "membershipAuthorityGeneration",
        Map.of(
            SelectedTargetConnectContextTestVectors.TENANT_ID,
            SelectedTargetConnectContextTestVectors.LARGE_COUNTER.add(BigInteger.ONE)));
    changedAuthorityCounter.put("authorityTuple", changedAuthorityTuple);
    assertSourceMismatch(changedAuthorityCounter, publicContext);

    Map<String, Object> changedRequestId =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    changedRequestId.put("requestId", "another-connect-request");
    assertSourceMismatch(changedRequestId, publicContext);

    Map<String, Object> changedJti =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    changedJti.put("jti", "another-connect-token-jti");
    assertSourceMismatch(changedJti, publicContext);

    Map<String, Object> changedIssuedAt =
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    changedIssuedAt.put(
        "iat", BigInteger.valueOf(SelectedTargetConnectContextTestVectors.SOURCE_ISSUED_AT + 1L));
    changedIssuedAt.put(
        "exp", BigInteger.valueOf(SelectedTargetConnectContextTestVectors.SOURCE_ISSUED_AT + 31L));
    assertSourceMismatch(changedIssuedAt, publicContext);

    Map<String, Object> privateSource =
        SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims();
    GatewayConnectContext privateContext = verify(sign(projectContext(privateSource)));

    Map<String, Object> changedLifecycle =
        SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims();
    String newLifecycle = "018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e";
    changedLifecycle.put("playtestLifecycleId", newLifecycle);
    @SuppressWarnings("unchecked")
    Map<String, Object> lifecycleTuple =
        new LinkedHashMap<>((Map<String, Object>) changedLifecycle.get("authorityTuple"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> originalGrants =
        (List<Map<String, Object>>) lifecycleTuple.get("privateRealmGrantVersions");
    Map<String, Object> changedGrant = new LinkedHashMap<>(originalGrants.getFirst());
    changedGrant.put("playtestLifecycleId", newLifecycle);
    lifecycleTuple.put("privateRealmGrantVersions", List.of(changedGrant));
    changedLifecycle.put("authorityTuple", lifecycleTuple);
    assertSourceMismatch(changedLifecycle, privateContext);

    Map<String, Object> changedLifecycleGeneration =
        SelectedTargetConnectContextTestVectors.privateSourceConnectTokenClaims();
    changedLifecycleGeneration.put("playtestStateGeneration", BigInteger.valueOf(4));
    assertSourceMismatch(changedLifecycleGeneration, privateContext);
  }

  @Test
  void rejectsGatewayExpiryBeyondTheVerifiedAccountSourceDeadline() throws Exception {
    Map<String, Object> source = SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    long issuedAt = SelectedTargetConnectContextTestVectors.SOURCE_ISSUED_AT;
    source.put("exp", BigInteger.valueOf(issuedAt + 10L));
    Map<String, Object> contextPayload = projectContext(source);
    contextPayload.put("expiresAt", BigInteger.valueOf(issuedAt + 11L));
    GatewayConnectContext validlySignedContext = verify(sign(contextPayload));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesGatewayContext(
                source, validlySignedContext));
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
  void verifiesExpiredAssertionOnlyAsExactImmutableHistoricalEvidence() throws Exception {
    Map<String, Object> source = historicalSource();
    Map<String, Object> claims = historicalContext(source);
    byte[] payload = json.writeValueAsBytes(claims);
    String envelope = GatewayConnectContextSignature.sign(payload, KID, keyPair.getPrivate());

    HistoricalGatewayConnectEvidence evidence = verifyHistorical(envelope);

    assertEquals(envelope, evidence.signedEnvelope());
    assertArrayEquals(payload, evidence.signedPayload());
    assertEquals(KID, evidence.kid());
    assertEquals(claims, evidence.claims());
    assertEquals(BigInteger.valueOf(NOW.getEpochSecond() - 100L), evidence.integer("issuedAt"));
    assertTrue(evidence.integer("expiresAt").longValueExact() < NOW.getEpochSecond());
    GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesHistoricalGatewayEvidence(
        source, evidence);

    byte[] exposedPayload = evidence.signedPayload();
    exposedPayload[0] ^= 1;
    assertArrayEquals(payload, evidence.signedPayload());
    assertThrows(
        UnsupportedOperationException.class, () -> evidence.claims().put("audience", "other"));
    @SuppressWarnings("unchecked")
    Map<String, Object> authorityTuple =
        (Map<String, Object>) evidence.claims().get("authorityTuple");
    assertThrows(
        UnsupportedOperationException.class,
        () -> authorityTuple.put("accountAuthorityGeneration", BigInteger.ZERO));
    assertFalse(evidence.toString().contains(envelope));
    assertFalse(evidence.toString().contains(new String(payload, StandardCharsets.UTF_8)));

    // This is the same old, validly signed assertion: the ordinary reader remains strict.
    assertThrows(IllegalArgumentException.class, () -> verify(envelope));
  }

  @Test
  void historicalEvidenceRejectsUnknownKeyAndForgedSignatureBeforePayloadInterpretation()
      throws Exception {
    String malformedJsonEnvelope =
        GatewayConnectContextSignature.sign(
            "{".getBytes(StandardCharsets.UTF_8), KID, keyPair.getPrivate());
    IllegalArgumentException unknownKey =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                GatewayConnectContextCodec.verifyHistoricalEvidence(
                    malformedJsonEnvelope, Map.of("another-key", keyPair.getPublic())));
    assertEquals("unknown Gateway verification key", unknownKey.getMessage());

    // A different valid signing key gives a well-formed, wrong-key signature. Flipping a
    // compressed-point byte can instead fail in the provider's point decoder.
    KeyPair wrongKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String forgedMalformedJsonEnvelope =
        GatewayConnectContextSignature.sign(
            "{".getBytes(StandardCharsets.UTF_8), KID, wrongKeyPair.getPrivate());
    IllegalArgumentException invalidSignature =
        assertThrows(
            IllegalArgumentException.class, () -> verifyHistorical(forgedMalformedJsonEnvelope));
    assertEquals("invalid Gateway context signature", invalidSignature.getMessage());
  }

  @Test
  void historicalEvidenceRejectsWrongReceiverAndOpenOrMalformedSchema() throws Exception {
    Map<String, Object> wrongAudience = historicalContext(historicalSource());
    wrongAudience.put("audience", "account-service");
    assertHistoricalRejected(wrongAudience);

    Map<String, Object> wrongRecipient = historicalContext(historicalSource());
    wrongRecipient.put("recipient", "another-service");
    assertHistoricalRejected(wrongRecipient);

    Map<String, Object> missingField = historicalContext(historicalSource());
    missingField.remove("realmId");
    assertHistoricalRejected(missingField);

    Map<String, Object> extraField = historicalContext(historicalSource());
    extraField.put("legacyTenantKey", "42");
    assertHistoricalRejected(extraField);

    Map<String, Object> invalidTarget = historicalContext(historicalSource());
    invalidTarget.put("tenantId", "not-a-canonical-uuid");
    assertHistoricalRejected(invalidTarget);
  }

  @Test
  void historicalEvidenceRejectsInvalidOriginalTimestampAndLifetimeGeometry() throws Exception {
    long issuedAt = NOW.getEpochSecond() - 100L;

    Map<String, Object> zeroLifetime = historicalContext(historicalSource());
    zeroLifetime.put("expiresAt", BigInteger.valueOf(issuedAt));
    assertHistoricalRejected(zeroLifetime);

    Map<String, Object> excessiveLifetime = historicalContext(historicalSource());
    excessiveLifetime.put(
        "expiresAt",
        BigInteger.valueOf(issuedAt + GatewayConnectContextCodec.MAX_CONTEXT_LIFETIME_SECONDS + 1));
    assertHistoricalRejected(excessiveLifetime);

    Map<String, Object> verificationAtExpiry = historicalContext(historicalSource());
    verificationAtExpiry.put("verifiedAt", verificationAtExpiry.get("expiresAt"));
    assertHistoricalRejected(verificationAtExpiry);

    Map<String, Object> excessiveFutureIssuedAt = historicalContext(historicalSource());
    excessiveFutureIssuedAt.put(
        "issuedAt",
        BigInteger.valueOf(issuedAt + GatewayConnectContextCodec.MAX_CLOCK_SKEW_SECONDS + 2));
    assertHistoricalRejected(excessiveFutureIssuedAt);

    Map<String, Object> unsupportedEpoch = historicalContext(historicalSource());
    BigInteger outsideInstantRange = new BigInteger("1000000000000000000000000");
    unsupportedEpoch.put("issuedAt", outsideInstantRange);
    unsupportedEpoch.put("verifiedAt", outsideInstantRange);
    unsupportedEpoch.put("expiresAt", outsideInstantRange.add(BigInteger.TEN));
    assertHistoricalRejected(unsupportedEpoch);
  }

  @Test
  void historicalSourceCorrespondenceRejectsChangedSourceAndSelectedTarget() throws Exception {
    Map<String, Object> source = historicalSource();
    HistoricalGatewayConnectEvidence original = verifyHistorical(sign(historicalContext(source)));

    Map<String, Object> changedSource = historicalSource();
    changedSource.put("connectScopeId", "different-original-source-scope");
    assertHistoricalSourceMismatch(changedSource, original);

    Map<String, Object> changedTarget = historicalContext(source);
    changedTarget.put("realmId", "018f8f0a-8c1d-7f9a-ad6a-bf4a312c0d8e");
    HistoricalGatewayConnectEvidence changedTargetEvidence = verifyHistorical(sign(changedTarget));
    assertHistoricalSourceMismatch(source, changedTargetEvidence);
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

  private HistoricalGatewayConnectEvidence verifyHistorical(String envelope) {
    return GatewayConnectContextCodec.verifyHistoricalEvidence(
        envelope, Map.of(KID, keyPair.getPublic()));
  }

  private String sign(Map<String, Object> claims) throws Exception {
    return GatewayConnectContextSignature.sign(
        json.writeValueAsBytes(claims), KID, keyPair.getPrivate());
  }

  private void assertRejected(Map<String, Object> claims) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> verify(sign(claims)));
  }

  private void assertHistoricalRejected(Map<String, Object> claims) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> verifyHistorical(sign(claims)));
  }

  private void assertSourceMismatch(
      Map<String, Object> sourceClaims, GatewayConnectContext verifiedContext) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesGatewayContext(
                sourceClaims, verifiedContext));
  }

  private void assertHistoricalSourceMismatch(
      Map<String, Object> sourceClaims, HistoricalGatewayConnectEvidence historicalEvidence) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesHistoricalGatewayEvidence(
                sourceClaims, historicalEvidence));
  }

  private static BigInteger nested(
      Map<String, Object> claims, String object, String nestedObject, String key) {
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) claims.get(object);
    @SuppressWarnings("unchecked")
    Map<String, Object> second = (Map<String, Object>) first.get(nestedObject);
    return (BigInteger) second.get(key);
  }

  private static String nestedString(Map<String, Object> claims, String object, String key) {
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) claims.get(object);
    return (String) nested.get(key);
  }

  private static Map<String, Object> validContext() {
    return projectContext(SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims());
  }

  private static Map<String, Object> historicalSource() {
    Map<String, Object> source = SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    long issuedAt = NOW.getEpochSecond() - 100L;
    source.put("iat", BigInteger.valueOf(issuedAt));
    source.put("exp", BigInteger.valueOf(issuedAt + 20L));
    return source;
  }

  private static Map<String, Object> historicalContext(Map<String, Object> source) {
    long issuedAt = ((BigInteger) source.get("iat")).longValueExact();
    return projectContext(source, issuedAt + 1L);
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
