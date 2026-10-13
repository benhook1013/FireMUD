package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Focused signature/profile tests; owner evidence is mocked and is not DB/Redis runtime proof. */
class AccountGameplayAdmissionInitialTokenAuthenticatorTest {
  private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String NAMESPACE = "firemud-test";
  private static final String KID = "account-key-42";
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID TOKEN_JTI = UUID.fromString("1a7c3d0c-ab15-4f86-b5af-9e29bc7d3543");
  private static final UUID OPERATION_ID = UUID.fromString("c5c31332-e560-41c8-a55a-97674e7a317c");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("e16fdce5-96eb-49e1-a9bd-3f429816cbf0");
  private static final String PEER_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final GrpcPeerIdentity GAME_SESSION_PEER =
      new GrpcPeerIdentity(PEER_URI, NAMESPACE, "game-session-service");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void acceptsCorrectlySignedInitialTokenAndExactCommittedMetadata() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);

    var observation =
        withPeer(
            GAME_SESSION_PEER,
            () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt()));

    assertThat(observation.candidate()).isSameAs(fixture.candidate());
    assertThat(observation.observedAtEpochSecond()).isEqualTo(NOW.getEpochSecond());
    assertThat(observation.toString()).doesNotContain(fixture.compactJwt());
    verify(fixture.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void rejectsAbsentPeerBeforeReadingJwksOrOwnerState() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);

    assertDenied(
        () ->
            withRootContext(
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    assertThat(fixture.jwksLoads().get()).isZero();
    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsWrongPeerBeforeReadingJwksOrOwnerState() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);
    GrpcPeerIdentity wrongService =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
            NAMESPACE,
            "account-service");

    assertDenied(
        () ->
            withPeer(
                wrongService,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    assertThat(fixture.jwksLoads().get()).isZero();
    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsInvalidSignatureOnOtherwiseValidInitialTokenBeforeOwnerRead() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);
    String invalidSignature = corruptSignature(fixture.compactJwt());

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(invalidSignature)));

    assertThat(fixture.jwksLoads().get()).isEqualTo(1);
    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsWrongTrustedNamespaceBeforeReadingJwksOrOwnerState() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);
    GrpcPeerIdentity otherNamespace =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/other-test/sa/game-session-service",
            "other-test",
            "game-session-service");

    assertDenied(
        () ->
            withPeer(
                otherNamespace,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    assertThat(fixture.jwksLoads().get()).isZero();
    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsAValidlySignedTenantBoundTokenAtTheInitialOnlyPolicy() throws Exception {
    String tenantId = "fb71a28c-b82f-4f79-9aaf-472324a0011e";
    Map<String, Object> tenantAuthority = new LinkedHashMap<>(initialAuthorityTuple());
    tenantAuthority.put("tenantAuthorityGeneration", Map.of(tenantId, "1"));
    tenantAuthority.put("membershipAuthorityGeneration", Map.of(tenantId, "1"));
    Map<String, Object> tenantClaims =
        Map.of(
            "tenantId", tenantId,
            "authorityTuple", tenantAuthority,
            "membershipVersion", Map.of(tenantId, "1"));
    Fixture fixture = fixture(tenantClaims, null, KID);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    assertThat(fixture.jwksLoads().get()).isZero();
    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsAnyRoleClaimsAtInitialAcquire() throws Exception {
    Fixture fixture = fixture(Map.of("globalRoles", List.of("admin")), null, KID);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsCompactHashThatDiffersFromCommittedCandidate() throws Exception {
    Fixture fixture = fixture(Map.of(), "a".repeat(64), KID);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    verify(fixture.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void rejectsGenerationOrFenceClaimsThatDifferFromCommittedInitialState() throws Exception {
    Fixture wrongGeneration = fixture(Map.of("tokenGeneration", "2"), null, KID);
    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () ->
                    wrongGeneration
                        .authenticator()
                        .authenticateInitialToken(wrongGeneration.compactJwt())));
    verify(wrongGeneration.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);

    Fixture wrongFence = fixture(Map.of("issuanceFence", "2"), null, KID);
    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () ->
                    wrongFence.authenticator().authenticateInitialToken(wrongFence.compactJwt())));
    verify(wrongFence.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void rejectsAuthorityTupleThatDoesNotMatchCommittedGlobalVersions() throws Exception {
    Map<String, Object> differentAuthority =
        GameSessionAccountDelegationProfile.authorityTuple(2L, 1L);
    Fixture fixture = fixture(Map.of("authorityTuple", differentAuthority), null, KID);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    verify(fixture.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void rejectsSignerIdentityOrDurableTimeMismatch() throws Exception {
    Fixture wrongSigner = fixture(Map.of(), null, "different-key");
    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () ->
                    wrongSigner
                        .authenticator()
                        .authenticateInitialToken(wrongSigner.compactJwt())));
    verify(wrongSigner.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);

    Fixture wrongExpiry = fixture(Map.of("exp", NOW.getEpochSecond() + 121L), null, KID);
    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () ->
                    wrongExpiry
                        .authenticator()
                        .authenticateInitialToken(wrongExpiry.compactJwt())));
    verify(wrongExpiry.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void rejectsExpiredButOtherwiseValidSignedTokenBeforeOwnerRead() throws Exception {
    long issuedAt = NOW.getEpochSecond() - 120L;
    Fixture fixture =
        fixture(
            Map.of(
                "iat", issuedAt,
                "nbf", issuedAt,
                "exp", NOW.getEpochSecond() - 1L),
            null,
            KID);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    verifyNoInteractions(fixture.owner());
  }

  @Test
  void rejectsChangedSubjectAndAccountAgainstOriginalCandidateWithSameJtiAndPresentedHash()
      throws Exception {
    UUID otherAccountId = UUID.fromString("751eb539-3238-4aa6-b86c-15d037a765c3");
    Fixture fixture =
        fixture(
            Map.of("sub", otherAccountId.toString(), "accountId", otherAccountId.toString()),
            null,
            KID);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    assertThat(fixture.candidate().tokenSha256()).isEqualTo(sha256(fixture.compactJwt()));
    verify(fixture.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void ownerFailureIsRedactedAndDenied() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);
    when(fixture.owner().requireCurrentActiveCommittedCandidateByJti(any()))
        .thenThrow(new IllegalStateException("storage detail must not escape"));

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    verify(fixture.owner()).requireCurrentActiveCommittedCandidateByJti(TOKEN_JTI);
  }

  @Test
  void refusesAmbientSqlTransactionBeforeJwksOrOwnerAccess() throws Exception {
    Fixture fixture = fixture(Map.of(), null, KID);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertDenied(
        () ->
            withPeer(
                GAME_SESSION_PEER,
                () -> fixture.authenticator().authenticateInitialToken(fixture.compactJwt())));

    assertThat(fixture.jwksLoads().get()).isZero();
    verifyNoInteractions(fixture.owner());
  }

  private static Fixture fixture(
      Map<String, Object> claimOverrides, String storedHash, String committedSignerKid)
      throws Exception {
    KeyPair keyPair = rsa3072();
    AtomicInteger jwksLoads = new AtomicInteger();
    AccountPublicJwksCache.SourceIdentity sourceIdentity = sourceIdentity();
    byte[] jwks = jwks(KID, (RSAPublicKey) keyPair.getPublic());
    AccountPublicJwksCache cache =
        new AccountPublicJwksCache(
            () -> {
              jwksLoads.incrementAndGet();
              return new AccountPublicJwksCache.PublicJwksSnapshot(sourceIdentity, jwks);
            },
            sourceIdentity,
            CLOCK,
            Duration.ofSeconds(30));
    AccountAsymmetricJwtVerifier verifier = new AccountAsymmetricJwtVerifier(cache, CLOCK);
    AccountGameplayDelegationCommittedIssuanceOwner owner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);

    Map<String, Object> claims = initialClaims();
    claims.putAll(claimOverrides);
    String compactJwt = sign(claims, keyPair);
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            OPERATION_ID,
            REQUEST_ID,
            ACCOUNT_ID,
            PEER_URI,
            CALLER_CONTEXT_ID,
            AccountGameplayCredentialRequestBindingFixture.binding(),
            "b".repeat(64),
            TOKEN_JTI,
            NOW.getEpochSecond(),
            NOW.getEpochSecond(),
            NOW.plusSeconds(120L).getEpochSecond());
    AccountAuthoritySnapshot authority =
        new AccountAuthoritySnapshot(ACCOUNT_ID, 1L, 1L, 1L, 1L, 1L, 1L, Optional.empty());
    CommittedCandidateVerificationData candidate = mock(CommittedCandidateVerificationData.class);
    when(candidate.identity()).thenReturn(identity);
    when(candidate.authoritySnapshot()).thenReturn(authority);
    when(candidate.tokenSha256()).thenReturn(storedHash == null ? sha256(compactJwt) : storedHash);
    when(candidate.signerKid()).thenReturn(committedSignerKid);
    when(candidate.signerGeneration()).thenReturn("42");
    when(owner.requireCurrentActiveCommittedCandidateByJti(any())).thenReturn(candidate);

    return new Fixture(
        new AccountGameplayAdmissionInitialTokenAuthenticator(verifier, owner, NAMESPACE, CLOCK),
        owner,
        candidate,
        compactJwt,
        jwksLoads);
  }

  private static Map<String, Object> initialClaims() {
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
    claims.put("authorityTuple", initialAuthorityTuple());
    claims.put("membershipVersion", Map.of());
    claims.put("issuanceFence", "1");
    return claims;
  }

  private static Map<String, Object> initialAuthorityTuple() {
    return GameSessionAccountDelegationProfile.authorityTuple(1L, 1L);
  }

  private static AccountPublicJwksCache.SourceIdentity sourceIdentity() {
    return new AccountPublicJwksCache.SourceIdentity(
        "prod",
        "prod-cluster-1",
        "11111111-1111-4111-8111-111111111111",
        "firemud-test",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
        "account-api-r1",
        "https://kubernetes.example:6443",
        "9".repeat(64));
  }

  private static byte[] jwks(String kid, RSAPublicKey key) {
    String jwk =
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
            + kid
            + "\",\"n\":\""
            + encode(unsigned(key.getModulus()))
            + "\",\"e\":\""
            + encode(unsigned(key.getPublicExponent()))
            + "\"}]}";
    return jwk.getBytes(StandardCharsets.UTF_8);
  }

  private static String sign(Map<String, Object> claims, KeyPair keyPair) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", KID, "typ", "JWT");
    String signingInput =
        encode(JSON.writeValueAsBytes(header))
            + "."
            + encode(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(claims)));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyPair.getPrivate());
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + encode(signature.sign());
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return bytes;
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
  }

  private static KeyPair rsa3072() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(new RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4));
    return generator.generateKeyPair();
  }

  private static void assertDenied(Runnable attempt) {
    assertThatThrownBy(attempt::run)
        .isInstanceOf(
            AccountGameplayAdmissionInitialTokenAuthenticator.InitialTokenAuthenticationException
                .class)
        .hasNoCause()
        .hasMessage("Initial Account token authentication failed");
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, java.util.function.Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    return withContext(context, action);
  }

  private static <T> T withRootContext(java.util.function.Supplier<T> action) {
    return withContext(Context.ROOT, action);
  }

  private static <T> T withContext(Context context, java.util.function.Supplier<T> action) {
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static String corruptSignature(String compactJwt) {
    String[] parts = compactJwt.split("\\.", -1);
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 0x01;
    return parts[0] + "." + parts[1] + "." + encode(signature);
  }

  private record Fixture(
      AccountGameplayAdmissionInitialTokenAuthenticator authenticator,
      AccountGameplayDelegationCommittedIssuanceOwner owner,
      CommittedCandidateVerificationData candidate,
      String compactJwt,
      AtomicInteger jwksLoads) {}
}
