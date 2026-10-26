package net.firedevops.firemud.accountservice.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PrepublicationIntent;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayAdmissionCallerVerifierTest {
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String ENVIRONMENT = "staging";
  private static final String CLUSTER = "cluster-admission-test";
  private static final String NAMESPACE = "firemud";
  private static final String CLUSTER_UID = "66666666-6666-4666-8666-666666666666";
  private static final String NAMESPACE_UID = "77777777-7777-4777-8777-777777777777";
  private static final String CONFIG_MAP_UID = "88888888-8888-4888-8888-888888888888";
  private static final String SECRET_UID = "99999999-9999-4999-8999-999999999999";
  private static final String API_BINDING_DIGEST = "a".repeat(64);
  private static final String API_CA_DIGEST = "b".repeat(64);
  private static final String OPERATION_DIGEST = "1".repeat(64);
  private static final String GENERATION_REQUEST_DIGEST = "2".repeat(64);
  private static final String GENERATION_RECEIPT_DIGEST = "3".repeat(64);
  private static final String SNAPSHOT_DIGEST = "4".repeat(64);
  private static final String CONFIG_MAP_SNAPSHOT_DIGEST = "6".repeat(64);
  private static final String KID = "account-admission-test";
  private static final String GAME_SESSION_WORKLOAD =
      "spiffe://firemud/ns/firemud/sa/game-session-service";
  private static final SourceIdentity JWKS_SOURCE_IDENTITY =
      new SourceIdentity(
          ENVIRONMENT,
          CLUSTER,
          CLUSTER_UID,
          NAMESPACE,
          NAMESPACE_UID,
          CONFIG_MAP_UID,
          "api-r1",
          "https://kubernetes.test:6443",
          API_CA_DIGEST);
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TOKEN_JTI = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID OTHER_ACCOUNT_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static KeyPair signingPair;
  private static KeyPair mismatchedPair;

  @BeforeAll
  static void generateRsaPairs() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    signingPair = generator.generateKeyPair();
    mismatchedPair = generator.generateKeyPair();
  }

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void verifiesSignedInitialCallerAgainstExactCommittedCandidate() throws Exception {
    Fixture fixture = new Fixture();
    String compactJwt = fixture.signedToken(signingPair, fixture.initialClaims());
    CommittedCandidateVerificationData committed =
        fixture.candidate(compactJwt, ACCOUNT_ID, false, false);
    when(fixture.currentIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI))
        .thenReturn(committed);

    AccountGameplayAdmissionCallerVerifier.VerifiedCaller verified =
        fixture.verifier.verifyPresentedCompactToken(compactJwt);

    assertSame(committed, verified.committedCandidate());
    assertEquals(ACCOUNT_ID, verified.identity().accountId());
    assertEquals(TOKEN_JTI, verified.identity().tokenJti());
    assertEquals(GameSessionAccountDelegationProfile.PROFILE, verified.tokenProfile());
    assertEquals(1L, verified.tokenIdentityFence());
    assertFalse(verified.toString().contains(compactJwt));
    verify(fixture.currentIssuanceOwner).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void reusesJwksCacheForSamePinAndStillReadsCurrentIssuanceForEachVerification() throws Exception {
    Fixture fixture = new Fixture();
    MutableClock clock = new MutableClock(NOW);
    AccountJwtJwksTrustedSource source = trustedSource(JWKS_SOURCE_IDENTITY, fixture.jwksJson);
    String compactJwt = fixture.signedToken(signingPair, fixture.initialClaims());
    CommittedCandidateVerificationData committed =
        fixture.candidate(compactJwt, ACCOUNT_ID, false, false);
    when(fixture.currentIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI))
        .thenReturn(committed);
    AccountGameplayAdmissionCallerVerifier verifier = fixture.verifierWith(source, clock);

    verifier.verifyPresentedCompactToken(compactJwt);
    verifier.verifyPresentedCompactToken(compactJwt);

    verify(source, times(2)).sourceIdentity();
    verify(source, times(1)).load();
    verify(fixture.currentIssuanceOwner, times(2))
        .requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void refreshesJwksCacheAfterMaximumAge() throws Exception {
    Fixture fixture = new Fixture();
    MutableClock clock = new MutableClock(NOW);
    AccountJwtJwksTrustedSource source = trustedSource(JWKS_SOURCE_IDENTITY, fixture.jwksJson);
    String compactJwt = fixture.signedToken(signingPair, fixture.initialClaims());
    CommittedCandidateVerificationData committed =
        fixture.candidate(compactJwt, ACCOUNT_ID, false, false);
    when(fixture.currentIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI))
        .thenReturn(committed);
    AccountGameplayAdmissionCallerVerifier verifier = fixture.verifierWith(source, clock);

    verifier.verifyPresentedCompactToken(compactJwt);
    clock.advanceSeconds(31);
    verifier.verifyPresentedCompactToken(compactJwt);

    verify(source, times(2)).load();
    verify(fixture.currentIssuanceOwner, times(2))
        .requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void replacesJwksCacheWhenTrustedSourceIdentityChanges() throws Exception {
    Fixture fixture = new Fixture();
    MutableClock clock = new MutableClock(NOW);
    SourceIdentity rotatedIdentity =
        new SourceIdentity(
            ENVIRONMENT,
            CLUSTER,
            CLUSTER_UID,
            NAMESPACE,
            NAMESPACE_UID,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "api-r2",
            "https://kubernetes.test:6443",
            API_CA_DIGEST);
    AtomicReference<SourceIdentity> activeIdentity = new AtomicReference<>(JWKS_SOURCE_IDENTITY);
    AtomicReference<String> activeJwks = new AtomicReference<>(fixture.jwksJson);
    AccountJwtJwksTrustedSource source = mock(AccountJwtJwksTrustedSource.class);
    when(source.sourceIdentity()).thenAnswer(invocation -> activeIdentity.get());
    when(source.load())
        .thenAnswer(
            invocation ->
                new PublicJwksSnapshot(
                    activeIdentity.get(), activeJwks.get().getBytes(StandardCharsets.UTF_8)));
    String oldToken = fixture.signedToken(signingPair, fixture.initialClaims());
    CommittedCandidateVerificationData oldCandidate =
        fixture.candidate(oldToken, ACCOUNT_ID, false, false);
    Map<String, Object> rotatedClaims = fixture.initialClaims();
    String rotatedToken = fixture.signedToken(mismatchedPair, rotatedClaims);
    CommittedCandidateVerificationData rotatedCandidate =
        fixture.candidate(rotatedToken, ACCOUNT_ID, false, false);
    when(fixture.currentIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI))
        .thenReturn(oldCandidate, rotatedCandidate);
    AccountGameplayAdmissionCallerVerifier verifier = fixture.verifierWith(source, clock);

    verifier.verifyPresentedCompactToken(oldToken);
    activeIdentity.set(rotatedIdentity);
    activeJwks.set("{\"keys\":[" + jwkJson(mismatchedPair, KID) + "]}");

    assertUnavailable(verifier, oldToken);
    assertSame(
        rotatedCandidate, verifier.verifyPresentedCompactToken(rotatedToken).committedCandidate());

    verify(source, times(3)).sourceIdentity();
    verify(source, times(2)).load();
    verify(fixture.currentIssuanceOwner, times(2))
        .requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void rejectsBadSignatureAndWrongInitialProfileBeforeCurrentnessLookup() throws Exception {
    Fixture fixture = new Fixture();
    String badSignature = fixture.signedToken(mismatchedPair, fixture.initialClaims());

    assertUnavailable(fixture.verifier, badSignature);
    verify(fixture.currentIssuanceOwner, never())
        .requireCurrentActiveCommittedCandidateByJti(any());

    Map<String, Object> tenantBoundClaims = fixture.initialClaims();
    tenantBoundClaims.put("tenantId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    String wrongProfile = fixture.signedToken(signingPair, tenantBoundClaims);

    assertUnavailable(fixture.verifier, wrongProfile);
    verify(fixture.currentIssuanceOwner, never())
        .requireCurrentActiveCommittedCandidateByJti(any());
  }

  @Test
  void rejectsCandidateHashIdentityAndAuthorityMismatchWithoutRetainingCredentialDetails()
      throws Exception {
    for (CandidateMismatch mismatch : CandidateMismatch.values()) {
      Fixture fixture = new Fixture();
      String compactJwt = fixture.signedToken(signingPair, fixture.initialClaims());
      CommittedCandidateVerificationData mismatchedCandidate =
          fixture.candidate(
              compactJwt,
              mismatch == CandidateMismatch.IDENTITY ? OTHER_ACCOUNT_ID : ACCOUNT_ID,
              mismatch == CandidateMismatch.HASH,
              mismatch == CandidateMismatch.AUTHORITY);
      when(fixture.currentIssuanceOwner.requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI))
          .thenReturn(mismatchedCandidate);

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> fixture.verifier.verifyPresentedCompactToken(compactJwt));

      assertNull(failure.getCause());
      assertEquals("Account gameplay caller verification is unavailable", failure.getMessage());
      assertFalse(failure.toString().contains(compactJwt));
      verify(fixture.currentIssuanceOwner).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
    }
  }

  @Test
  void refusesAmbientTransactionBeforeTrustedSourceOrCurrentnessReads() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.verifier.verifyPresentedCompactToken("credential-not-retained"));

    assertNull(failure.getCause());
    verifyNoInteractions(
        fixture.materializerTrust,
        fixture.configMapClient,
        fixture.desiredStateRepository,
        fixture.publicationRepository,
        fixture.currentIssuanceOwner);
  }

  private static void assertUnavailable(
      AccountGameplayAdmissionCallerVerifier verifier, String compactJwt) {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> verifier.verifyPresentedCompactToken(compactJwt));
    assertNull(failure.getCause());
    assertEquals("Account gameplay caller verification is unavailable", failure.getMessage());
    assertFalse(failure.toString().contains(compactJwt));
  }

  private static AccountJwtJwksTrustedSource trustedSource(
      SourceIdentity identity, String jwksJson) {
    AccountJwtJwksTrustedSource source = mock(AccountJwtJwksTrustedSource.class);
    when(source.sourceIdentity()).thenReturn(identity);
    when(source.load())
        .thenReturn(new PublicJwksSnapshot(identity, jwksJson.getBytes(StandardCharsets.UTF_8)));
    return source;
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private enum CandidateMismatch {
    HASH,
    IDENTITY,
    AUTHORITY
  }

  private static final class Fixture {
    private final AccountJwtJwksConfigMapClient configMapClient =
        mock(AccountJwtJwksConfigMapClient.class);
    private final AccountJwtSignerMaterializerTrustBinding materializerTrust =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    private final AccountJwtSignerDesiredStateRepository desiredStateRepository =
        mock(AccountJwtSignerDesiredStateRepository.class);
    private final AccountJwtJwksPublicationRepository publicationRepository =
        mock(AccountJwtJwksPublicationRepository.class);
    private final PlatformTransactionManager transactionManager =
        mock(PlatformTransactionManager.class);
    private final AccountGameplayDelegationCommittedIssuanceOwner currentIssuanceOwner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);
    private final Binding materializerBinding;
    private final AccountJwtSignerDesiredStateRepository.Binding accountBinding;
    private final TrustFence trustFence;
    private final BindingIdentity apiIdentity;
    private final String jwksJson;
    private final String generationMarkerJson;
    private final GenerationRequest generationRequest;
    private final GenerationResult generationResult;
    private final EnrollmentIdentity enrollmentIdentity;
    private final DesiredState desiredState;
    private final PublicationEvidence publicationEvidence;
    private final ConfigMapSnapshot configMapSnapshot;
    private final AccountJwtJwksTrustedSource trustedJwksSource;
    private final AccountGameplayAdmissionCallerVerifier verifier;

    private Fixture() {
      String expectedPeer = "spiffe://firemud/ns/firemud/sa/jwt-signer-materializer";
      String trustDigest =
          AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
              "materializer-r1",
              ENVIRONMENT,
              CLUSTER,
              NAMESPACE,
              CLUSTER_UID,
              NAMESPACE_UID,
              expectedPeer,
              List.of("c".repeat(64)));
      materializerBinding =
          new Binding(
              ENVIRONMENT,
              CLUSTER,
              NAMESPACE,
              CLUSTER_UID,
              NAMESPACE_UID,
              expectedPeer,
              List.of("c".repeat(64)),
              "materializer-r1",
              trustDigest);
      accountBinding = materializerBinding.accountBinding();
      trustFence = new TrustFence(CLUSTER_UID, NAMESPACE_UID, trustDigest, "materializer-r1");
      apiIdentity =
          new BindingIdentity(
              API_BINDING_DIGEST,
              "api-r1",
              ENVIRONMENT,
              CLUSTER,
              NAMESPACE,
              CLUSTER_UID,
              NAMESPACE_UID,
              "https://kubernetes.test:6443",
              "kubernetes.test",
              API_CA_DIGEST,
              "system:serviceaccount:firemud:account-service");
      String jwk = jwkJson(signingPair, KID);
      jwksJson = "{\"keys\":[" + jwk + "]}";
      String publicFingerprint = fingerprint((RSAPublicKey) signingPair.getPublic());
      UUID generationOperationId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
      String publicJwkJson = jwk;
      String generation = "1";
      String secretResourceVersion = "10";
      generationRequest =
          new GenerationRequest(
              GenerationPhase.GENERATION_RECORDED,
              generationOperationId,
              OPERATION_DIGEST,
              GENERATION_REQUEST_DIGEST,
              2L,
              accountBinding,
              trustFence,
              AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME,
              generation,
              KID,
              "RS256",
              "MATERIALIZE_PENDING",
              List.of("pending"),
              Optional.empty(),
              Optional.empty(),
              SECRET_UID,
              secretResourceVersion,
              GENERATION_RECEIPT_DIGEST,
              publicFingerprint,
              publicJwkJson,
              "11");
      generationResult =
          new GenerationResult(
              generationOperationId,
              accountBinding,
              OPERATION_DIGEST,
              GENERATION_REQUEST_DIGEST,
              2L,
              trustFence,
              AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME,
              SECRET_UID,
              secretResourceVersion,
              "11",
              generation,
              KID,
              "RS256",
              publicFingerprint,
              publicJwkJson,
              GENERATION_RECEIPT_DIGEST);
      enrollmentIdentity =
          new EnrollmentIdentity(
              CLUSTER_UID,
              NAMESPACE_UID,
              trustDigest,
              "materializer-r1",
              API_BINDING_DIGEST,
              "api-r1",
              CONFIG_MAP_UID,
              "40",
              CONFIG_MAP_SNAPSHOT_DIGEST);
      desiredState =
          new DesiredState(
              accountBinding,
              2L,
              Optional.empty(),
              Optional.empty(),
              Optional.of(generationOperationId),
              Optional.empty(),
              Optional.of(enrollmentIdentity));
      generationMarkerJson =
          markerJson(
              generationOperationId,
              OPERATION_DIGEST,
              GENERATION_REQUEST_DIGEST,
              GENERATION_RECEIPT_DIGEST,
              materializerBinding,
              apiIdentity,
              generation,
              KID,
              publicFingerprint);
      PrepublicationIntent intent =
          PrepublicationIntent.create(
              generationResult,
              generationRequest,
              API_BINDING_DIGEST,
              "api-r1",
              CONFIG_MAP_UID,
              "41",
              SNAPSHOT_DIGEST,
              jwksJson,
              generationMarkerJson);
      PublicationReceipt receipt = PublicationReceipt.create(intent, "42");
      publicationEvidence = new PublicationEvidence(intent, Optional.of(receipt), Optional.empty());
      configMapSnapshot =
          new ConfigMapSnapshot(
              CONFIG_MAP_UID,
              "42",
              Map.of(
                  AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                  jwksJson,
                  AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                  generationMarkerJson,
                  "operator.note",
                  "retained"));

      when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
      when(materializerTrust.current()).thenReturn(Optional.of(materializerBinding));
      when(configMapClient.identity()).thenReturn(apiIdentity);
      when(configMapClient.observe()).thenReturn(configMapSnapshot);
      when(desiredStateRepository.readEnrollmentState(eq(accountBinding)))
          .thenReturn(Optional.of(desiredState));
      when(desiredStateRepository.readCurrentGenerationRequest(eq(accountBinding), eq(trustFence)))
          .thenReturn(generationRequest);
      when(desiredStateRepository.readCurrentGenerationResult(eq(accountBinding), eq(trustFence)))
          .thenReturn(generationResult);
      when(publicationRepository.readCurrentPublication(eq(accountBinding), eq(trustFence)))
          .thenReturn(Optional.of(publicationEvidence));
      trustedJwksSource =
          new AccountJwtJwksTrustedSource(
              configMapClient,
              materializerTrust,
              desiredStateRepository,
              publicationRepository,
              transactionManager);
      verifier =
          new AccountGameplayAdmissionCallerVerifier(
              trustedJwksSource, currentIssuanceOwner, CLOCK);
    }

    private AccountGameplayAdmissionCallerVerifier verifierWith(
        AccountJwtJwksTrustedSource source, Clock clock) {
      return new AccountGameplayAdmissionCallerVerifier(source, currentIssuanceOwner, clock);
    }

    private Map<String, Object> initialClaims() {
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("iss", GameSessionAccountDelegationProfile.ISSUER);
      claims.put("sub", ACCOUNT_ID.toString());
      claims.put("jti", TOKEN_JTI.toString());
      claims.put("accountId", ACCOUNT_ID.toString());
      claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
      claims.put("iat", NOW.getEpochSecond());
      claims.put("nbf", NOW.getEpochSecond());
      claims.put("exp", NOW.plusSeconds(120L).getEpochSecond());
      claims.put("tokenGeneration", "1");
      claims.put("authorityTuple", GameSessionAccountDelegationProfile.authorityTuple(1L, 1L));
      claims.put("membershipVersion", Map.of());
      claims.put("issuanceFence", "1");
      return claims;
    }

    private CommittedCandidateVerificationData candidate(
        String compactJwt, UUID identityAccountId, boolean wrongHash, boolean wrongAuthority) {
      AccountGameplayDelegationPendingIdentity identity =
          new AccountGameplayDelegationPendingIdentity(
              ISSUANCE_OPERATION_ID,
              REQUEST_ID,
              identityAccountId,
              GAME_SESSION_WORKLOAD,
              CALLER_CONTEXT_ID,
              AccountGameplayCredentialRequestBindingFixture.binding(),
              "d".repeat(64),
              TOKEN_JTI,
              NOW.getEpochSecond(),
              NOW.getEpochSecond(),
              NOW.plusSeconds(120L).getEpochSecond());
      UUID authorityAccountId = identityAccountId;
      AccountAuthoritySnapshot authoritySnapshot =
          wrongAuthority
              ? new AccountAuthoritySnapshot(
                  authorityAccountId,
                  1L,
                  1L,
                  2L,
                  2L,
                  2L,
                  2L,
                  Optional.of(
                      new AccountSecurityCutoff(
                          "2", "account:auth-authority:v1:account/" + authorityAccountId, "1")))
              : new AccountAuthoritySnapshot(
                  authorityAccountId, 1L, 1L, 1L, 1L, 1L, 1L, Optional.empty());
      CommittedCandidateVerificationData candidate = mock(CommittedCandidateVerificationData.class);
      when(candidate.identity()).thenReturn(identity);
      when(candidate.authoritySnapshot()).thenReturn(authoritySnapshot);
      when(candidate.signerKid()).thenReturn(KID);
      when(candidate.tokenSha256()).thenReturn(wrongHash ? "0".repeat(64) : sha256(compactJwt));
      return candidate;
    }

    private String signedToken(KeyPair keyPair, Map<String, Object> claims) throws Exception {
      String header =
          base64Url(JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", KID, "typ", "JWT")));
      String payload = base64Url(JSON.writeValueAsBytes(claims));
      String input = header + "." + payload;
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keyPair.getPrivate());
      signature.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + base64Url(signature.sign());
    }
  }

  private static String markerJson(
      UUID operationId,
      String operationDigest,
      String requestDigest,
      String receiptDigest,
      Binding binding,
      BindingIdentity api,
      String generation,
      String kid,
      String publicFingerprint) {
    return "{\"schemaVersion\":1,\"phase\":\"PREPUBLISHED\",\"operationId\":\""
        + operationId
        + "\",\"operationDigest\":\""
        + operationDigest
        + "\",\"generationRequestDigest\":\""
        + requestDigest
        + "\",\"generationReceiptDigest\":\""
        + receiptDigest
        + "\",\"binding\":{\"environmentId\":\""
        + binding.environmentId()
        + "\",\"clusterId\":\""
        + binding.clusterId()
        + "\",\"namespace\":\""
        + binding.namespace()
        + "\",\"expectedClusterIncarnationUid\":\""
        + binding.expectedClusterIncarnationUid()
        + "\",\"expectedNamespaceUid\":\""
        + binding.expectedNamespaceUid()
        + "\",\"trustBindingDigest\":\""
        + binding.bindingDigest()
        + "\",\"trustConfigRevision\":\""
        + binding.configRevision()
        + "\",\"apiBindingDigest\":\""
        + api.bindingDigest()
        + "\",\"apiConfigRevision\":\""
        + api.configRevision()
        + "\"},\"publicConfigMap\":{\"name\":\"jwt-jwks\"},"
        + "\"expectedDurableActive\":{\"present\":false},"
        + "\"expectedPublishedActive\":{\"present\":false},"
        + "\"pending\":{\"generation\":\""
        + generation
        + "\",\"kid\":\""
        + kid
        + "\",\"algorithm\":\"RS256\",\"publicKeyFingerprint\":\""
        + publicFingerprint
        + "\"}}";
  }

  private static String jwkJson(KeyPair keyPair, String kid) {
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    String json =
        "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
            + kid
            + "\",\"key_ops\":[\"verify\"],\"n\":\""
            + base64Url(unsigned(publicKey.getModulus()))
            + "\",\"e\":\""
            + base64Url(unsigned(publicKey.getPublicExponent()))
            + "\"}";
    try {
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (java.io.IOException malformedJwk) {
      throw new IllegalStateException(
          "Generated test JWK could not be canonicalized", malformedJwk);
    }
  }

  private static String fingerprint(RSAPublicKey publicKey) {
    String canonicalRsaPublicJwk =
        "{\"e\":\""
            + base64Url(unsigned(publicKey.getPublicExponent()))
            + "\",\"kty\":\"RSA\",\"n\":\""
            + base64Url(unsigned(publicKey.getModulus()))
            + "\"}";
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalRsaPublicJwk.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] encoded = value.toByteArray();
    return encoded.length > 1 && encoded[0] == 0
        ? Arrays.copyOfRange(encoded, 1, encoded.length)
        : encoded;
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
