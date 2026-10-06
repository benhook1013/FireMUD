package net.firedevops.firemud.accountservice.service.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountMountedJwtSignerBundleTest {
  private static final String ENVIRONMENT = "staging";
  private static final String CLUSTER = "cluster-a";
  private static final String NAMESPACE = "firemud";
  private static final String OPERATION = "7af097ea-b1d1-42ea-9f24-46aeb211a779";
  private static final String GENERATION = "42";
  private static final String KID = "account-key-42";
  private static KeyPair rsa3072;

  @TempDir Path tempDirectory;
  private Path privateMount;
  private Path publicMount;

  @BeforeAll
  static void generateEphemeralRsa3072Only() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(new RSAKeyGenParameterSpec(3_072, RSAKeyGenParameterSpec.F4));
    rsa3072 = generator.generateKeyPair();
  }

  @BeforeEach
  void writeValidMountedPair() throws Exception {
    privateMount = Files.createDirectory(tempDirectory.resolve("private-mount"));
    publicMount = Files.createDirectory(tempDirectory.resolve("public-mount"));
    Files.writeString(privateMount.resolve("current.key"), bundleJson(identity(), rsa3072));
    Files.writeString(publicMount.resolve("jwks.json"), jwksJson(identity(), rsa3072));
  }

  @Test
  void observesCorrespondingMountedKeyWithoutReturningSigningAuthority() throws Exception {
    AccountMountedJwtSignerBundle.LocalObservation observation = observe(identity());

    assertEquals(identity(), observation.expectedIdentity());
    assertEquals(identity().publicKeyFingerprint(), observation.publicKeyFingerprint());
    assertTrue(observation.localPrivatePublicCorrespondenceObserved());
    assertTrue(observation.toString().contains("authorization=none"));
    assertTrue(observation.toString().contains("provisioning=unproved"));
    assertFalse(observation.toString().contains(privateKeyText(rsa3072)));
    assertThrows(
        NoSuchMethodException.class, () -> observation.getClass().getMethod("sign", byte[].class));
    assertThrows(
        NoSuchFieldException.class, () -> observation.getClass().getDeclaredField("privateKey"));

    byte[] nonce = new byte[32];
    Arrays.fill(nonce, (byte) 7);
    byte[] originalNonce = nonce.clone();
    byte[] challenge = AccountMountedJwtSignerBundle.correspondenceChallenge(nonce);
    assertEquals(0, challenge[0]);
    assertFalse(isAscii(challenge));
    assertFalse(new String(challenge, StandardCharsets.ISO_8859_1).contains("."));
    assertArrayEquals(originalNonce, nonce);
  }

  @Test
  void signsOnlyFixedReadinessShapesAndDeliversCompactBytesTransiently() throws Exception {
    long issuedAt = 1_800_000_000L;
    AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec spec =
        new AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec(
            "account-service",
            AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
            AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE,
            AccountMountedJwtSignerBundle.REPRESENTATIVE_AUDIENCE,
            UUID.fromString("6f512d65-06ce-4d6c-9cf0-c2734185e1d8"),
            GENERATION,
            KID,
            issuedAt,
            issuedAt + 180);

    String[] deliveredHash = new String[1];
    byte[][] deliveredBytes = new byte[1][];
    AccountMountedJwtSignerBundle.SignedProbeDigest first =
        AccountMountedJwtSignerBundle.signReadinessProbeDigest(
            privateMount,
            Path.of("current.key"),
            publicMount,
            Path.of("jwks.json"),
            identity(),
            spec,
            (digest, compactJwt) -> {
              deliveredBytes[0] = compactJwt;
              assertTrue(compactJwt.length > 0);
              assertTrue(compactJwt.length < 16 * 1024);
              String compact = new String(compactJwt, StandardCharsets.US_ASCII);
              String[] parts = compact.split("\\.", -1);
              assertEquals(3, parts.length);
              byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
              byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
              try {
                try {
                  java.security.Signature verifier =
                      java.security.Signature.getInstance("SHA256withRSA");
                  verifier.initVerify((RSAPublicKey) rsa3072.getPublic());
                  verifier.update(signingInput);
                  assertTrue(verifier.verify(signature));
                } catch (java.security.GeneralSecurityException exception) {
                  org.junit.jupiter.api.Assertions.fail(
                      "Expected readiness probe signature to verify with the mounted public key",
                      exception);
                }
                assertTrue(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .contains("\"tokenGeneration\":\"1\""));
                String claims =
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                assertTrue(claims.contains("\"tenantAuthorityGeneration\":{}"));
                assertTrue(claims.contains("\"membershipAuthorityGeneration\":{}"));
                assertTrue(claims.contains("\"membershipVersion\":{}"));
                assertFalse(claims.contains("scopedRoles"));
                assertFalse(claims.contains("globalRoles"));
                deliveredHash[0] = sha256(compactJwt);
                assertEquals(digest.compactTokenSha256(), deliveredHash[0]);
              } finally {
                Arrays.fill(signingInput, (byte) 0);
                Arrays.fill(signature, (byte) 0);
              }
            });
    assertTrue(Arrays.equals(new byte[deliveredBytes[0].length], deliveredBytes[0]));

    AccountMountedJwtSignerBundle.SignedProbeDigest retry =
        AccountMountedJwtSignerBundle.signReadinessProbeDigest(
            privateMount,
            Path.of("current.key"),
            publicMount,
            Path.of("jwks.json"),
            identity(),
            spec,
            (digest, compactJwt) -> {
              deliveredBytes[0] = compactJwt;
              String hash = sha256(compactJwt);
              assertEquals(digest.compactTokenSha256(), hash);
              assertEquals(deliveredHash[0], hash);
            });
    assertTrue(Arrays.equals(new byte[deliveredBytes[0].length], deliveredBytes[0]));

    assertEquals(first.compactTokenSha256(), retry.compactTokenSha256());
    assertEquals(spec.jti(), first.jti());
    assertEquals(GENERATION, first.targetGeneration());
    assertFalse(first.toString().contains("compactJwt"));
    assertThrows(NoSuchMethodException.class, () -> first.getClass().getMethod("compactJwt"));
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () ->
            new AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec(
                "account-service",
                AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
                AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE,
                "another-audience",
                UUID.randomUUID(),
                GENERATION,
                KID,
                issuedAt,
                issuedAt + 180));
  }

  @Test
  void signsOnlyTheThreeExactAccountRepresentativeProfileShapes() throws Exception {
    long issuedAt = 1_800_000_000L;
    Map<String, String> audiences =
        Map.of(
            "control-ui",
            "control-ui",
            "player-bootstrap",
            "player-bootstrap",
            AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE,
            AccountMountedJwtSignerBundle.REPRESENTATIVE_AUDIENCE);

    for (Map.Entry<String, String> profile : audiences.entrySet()) {
      AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec spec =
          new AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec(
              "account-service",
              AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
              profile.getKey(),
              profile.getValue(),
              UUID.randomUUID(),
              GENERATION,
              KID,
              issuedAt,
              issuedAt + 180);
      AccountMountedJwtSignerBundle.signReadinessProbeDigest(
          privateMount,
          Path.of("current.key"),
          publicMount,
          Path.of("jwks.json"),
          identity(),
          spec,
          (digest, compactJwt) -> {
            String[] parts = new String(compactJwt, StandardCharsets.US_ASCII).split("\\.", -1);
            assertEquals(3, parts.length);
            String claims =
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            assertTrue(claims.contains("\"aud\":\"" + profile.getValue() + "\""));
            assertTrue(claims.contains("\"accountId\":\"00000000-0000-4000-8000-000000000001\""));
            assertTrue(
                claims.contains("\"scopedRoles\":{}")
                    == !profile
                        .getKey()
                        .equals(AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE));
            assertFalse(claims.contains("\"globalRoles\""));
            assertTrue(claims.contains("\"privateRealmGrantVersions\":[]"));
            if (GameSessionAccountDelegationProfile.PROFILE.equals(profile.getKey())) {
              assertTrue(claims.contains("\"tokenGeneration\":\"1\""));
              assertTrue(claims.contains("\"issuerAuthGeneration\":\"1\""));
              assertTrue(claims.contains("\"accountAuthorityGeneration\":\"1\""));
              assertTrue(claims.contains("\"issuanceFence\":\"1\""));
            } else {
              assertTrue(claims.contains("\"tokenGeneration\":1"));
              assertTrue(claims.contains("\"issuanceFence\":1"));
            }
            assertEquals(digest.compactTokenSha256(), sha256(compactJwt));
          });
    }
  }

  @Test
  void signsClosedAccountDelegationClaimsFromPersistedIdentityAndWipesTransientBytes()
      throws Exception {
    long issuedAt = 1_800_000_000L;
    UUID accountId = UUID.fromString("a6a2af33-2e50-4092-934b-43c5bce12f7b");
    UUID operationId = UUID.fromString("c5c31332-e560-41c8-a55a-97674e7a317c");
    UUID requestId = UUID.fromString("f14ca47a-2316-47b7-a38f-f9b693ff8afc");
    UUID contextId = UUID.fromString("e16fdce5-96eb-49e9-a1bd-3f429816cbf0");
    UUID jti = UUID.fromString("1a7c3d0c-ab15-4f86-b5af-9e29bc7d3543");
    String requestDigest = "ab".repeat(32);
    AccountAuthoritySnapshot authority =
        new AccountAuthoritySnapshot(accountId, 7, 7, 1, 1, 1, 1, Optional.empty());
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            operationId,
            requestId,
            accountId,
            "spiffe://firemud/ns/firemud/sa/game-session-service",
            contextId,
            AccountGameplayCredentialRequestBindingFixture.binding(),
            requestDigest,
            jti,
            issuedAt,
            issuedAt,
            issuedAt + 120);

    byte[][] transientBytes = new byte[1][];
    String[] observedHash = new String[1];
    String[] transientHash = new String[1];
    AccountMountedJwtSignerBundle.SignedDelegationDigest signed =
        AccountMountedJwtSignerBundle.signCommittedGameplayDelegationDigest(
            privateMount,
            Path.of("current.key"),
            publicMount,
            Path.of("jwks.json"),
            identity(),
            new AccountMountedJwtSignerBundle.DelegationSigningSpec(identity, authority),
            (digest, compactJwt) -> {
              transientBytes[0] = compactJwt;
              assertEquals(operationId, digest.issuanceOperationId());
              assertEquals(requestId, digest.requestId());
              assertEquals(jti, digest.jti());
              assertEquals(identity().operationId(), digest.signerOperationId());
              assertEquals(GENERATION, digest.signerGeneration());
              assertEquals(KID, digest.kid());

              String compact = new String(compactJwt, StandardCharsets.US_ASCII);
              String claims =
                  new String(
                      Base64.getUrlDecoder().decode(compact.split("\\.", -1)[1]),
                      StandardCharsets.UTF_8);
              assertTrue(claims.contains("\"tokenGeneration\":\"1\""));
              assertTrue(claims.contains("\"issuanceFence\":\"1\""));
              assertTrue(claims.contains("\"issuerAuthGeneration\":\"7\""));
              assertTrue(claims.contains("\"accountAuthorityGeneration\":\"1\""));
              transientHash[0] = sha256(compactJwt);
              String[] parts = compact.split("\\.", -1);
              assertEquals(3, parts.length);
              byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
              byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
              try {
                java.security.Signature verifier =
                    java.security.Signature.getInstance("SHA256withRSA");
                verifier.initVerify((RSAPublicKey) rsa3072.getPublic());
                verifier.update(signingInput);
                assertTrue(verifier.verify(signature));
              } catch (java.security.GeneralSecurityException exception) {
                org.junit.jupiter.api.Assertions.fail(
                    "Expected Account delegation signature to verify with its mounted public key",
                    exception);
              } finally {
                Arrays.fill(signingInput, (byte) 0);
                Arrays.fill(signature, (byte) 0);
              }

              GameSessionAccountDelegationRegistryRecord pending =
                  GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                      compact,
                      GENERATION,
                      new IssuanceBinding(
                          operationId.toString(),
                          requestId.toString(),
                          requestDigest,
                          accountId.toString()),
                      authority,
                      new EvidenceBundleReference("1", "11", "9", "3", "d".repeat(64)),
                      issuedAt,
                      GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
              assertEquals(jti.toString(), pending.jti());
              assertEquals("d".repeat(64), pending.evidenceBundleReference().canonicalSha256());
              assertEquals(operationId.toString(), pending.operationId());
              assertEquals(requestId.toString(), pending.requestId());
              assertEquals(sha256(compactJwt), pending.tokenHash());
              observedHash[0] = pending.tokenHash();
              assertFalse(compact.contains("tokenProfile"));
              assertFalse(compact.contains("tokenType"));
            });

    assertEquals(observedHash[0], signed.compactTokenSha256());
    assertEquals(transientHash[0], signed.compactTokenSha256());
    assertTrue(Arrays.equals(new byte[transientBytes[0].length], transientBytes[0]));
    assertFalse(signed.toString().contains(signed.compactTokenSha256()));
  }

  @Test
  void mountedSignerCreatesDistinctBoundPendingClaimsWithRealSignatureAndNoInitialAdoption()
      throws Exception {
    long issued = 1_800_000_000L;
    UUID account = UUID.fromString("a6a2af33-2e50-4092-934b-43c5bce12f7b");
    String tenant = "22222222-2222-4222-8222-222222222222";
    var pending =
        new AccountGameplayDelegationPendingIdentity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            account,
            "spiffe://firemud/ns/firemud/sa/game-session-service",
            UUID.randomUUID(),
            AccountGameplayCredentialRequestBindingFixture.binding(),
            "a".repeat(64),
            UUID.randomUUID(),
            issued,
            issued,
            issued + 120);
    var authority = new AccountAuthoritySnapshot(account, 7, 7, 1, 1, 1, 1, Optional.empty());
    var tuple =
        new java.util.LinkedHashMap<String, Object>(
            GameSessionAccountDelegationProfile.authorityTuple(7, 1));
    tuple.put("tenantAuthorityGeneration", Map.of(tenant, "5"));
    tuple.put("membershipAuthorityGeneration", Map.of(tenant, "7"));
    byte[][] captured = new byte[1][];
    AccountMountedJwtSignerBundle.signCommittedGameplayDelegationDigest(
        privateMount,
        Path.of("current.key"),
        publicMount,
        Path.of("jwks.json"),
        identity(),
        new AccountMountedJwtSignerBundle.DelegationSigningSpec(
            pending,
            authority,
            Optional.of(
                new AccountMountedJwtSignerBundle.BoundDelegationClaims(
                    tenant, tuple, Map.of(tenant, "3")))),
        (digest, bytes) -> {
          captured[0] = bytes;
          String jwt = new String(bytes, StandardCharsets.US_ASCII);
          String[] parts = jwt.split("\\.");
          try {
            var verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(rsa3072.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
          } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
          }
          var binding =
              new IssuanceBinding(
                  pending.operationId().toString(),
                  pending.requestId().toString(),
                  pending.requestDigest(),
                  account.toString());
          var evidence = new EvidenceBundleReference("1", "1", "1", "1", "b".repeat(64));
          var registry =
              GameSessionAccountDelegationRegistryRecord.fromAccountSignedBoundCompactJwt(
                  jwt,
                  GENERATION,
                  binding,
                  authority,
                  evidence,
                  tenant,
                  tuple,
                  Map.of(tenant, "3"),
                  issued,
                  16384);
          assertEquals("pending", registry.state());
          assertEquals(tenant, registry.fields().get("tenantId"));
          assertEquals(Map.of(tenant, "3"), registry.fields().get("membershipVersion"));
          byte[] canonicalRegistry = registry.toCanonicalJsonBytes(16384);
          assertArrayEquals(
              canonicalRegistry,
              GameSessionAccountDelegationRegistryRecord.decode(canonicalRegistry, 16384)
                  .toCanonicalJsonBytes(16384));
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                      jwt, GENERATION, binding, authority, evidence, issued, 16384));
        });
    assertArrayEquals(new byte[captured[0].length], captured[0]);
  }

  @Test
  void callbackFailureDoesNotRetainTransientCredentialAndWipesCallbackBytes() {
    long issuedAt = 1_800_000_000L;
    UUID accountId = UUID.fromString("a6a2af33-2e50-4092-934b-43c5bce12f7b");
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            UUID.fromString("c5c31332-e560-41c8-a55a-97674e7a317c"),
            UUID.fromString("f14ca47a-2316-47b7-a38f-f9b693ff8afc"),
            accountId,
            "spiffe://firemud/ns/firemud/sa/game-session-service",
            UUID.fromString("e16fdce5-96eb-49e9-a1bd-3f429816cbf0"),
            AccountGameplayCredentialRequestBindingFixture.binding(),
            "ab".repeat(32),
            UUID.fromString("1a7c3d0c-ab15-4f86-b5af-9e29bc7d3543"),
            issuedAt,
            issuedAt,
            issuedAt + 120);
    AccountAuthoritySnapshot authority =
        new AccountAuthoritySnapshot(accountId, 7, 7, 1, 1, 1, 1, Optional.empty());
    byte[][] callbackBytes = new byte[1][];
    String[] transientJwt = new String[1];

    AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException failure =
        assertThrows(
            AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
            () ->
                AccountMountedJwtSignerBundle.signCommittedGameplayDelegationDigest(
                    privateMount,
                    Path.of("current.key"),
                    publicMount,
                    Path.of("jwks.json"),
                    identity(),
                    new AccountMountedJwtSignerBundle.DelegationSigningSpec(identity, authority),
                    (digest, compactJwt) -> {
                      callbackBytes[0] = compactJwt;
                      transientJwt[0] = new String(compactJwt, StandardCharsets.US_ASCII);
                      throw new IllegalStateException(
                          "transient callback failure: " + transientJwt[0]);
                    }));

    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains(transientJwt[0]));
    assertFalse(failure.toString().contains(transientJwt[0]));
    assertTrue(Arrays.equals(new byte[callbackBytes[0].length], callbackBytes[0]));
  }

  @Test
  void rejectsEveryExpectedIdentityMismatch() {
    AccountMountedJwtSignerBundle.ExpectedIdentity valid = identity();
    AccountMountedJwtSignerBundle.ExpectedIdentity[] mismatches = {
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          "production",
          valid.clusterId(),
          valid.namespace(),
          valid.operationId(),
          valid.generation(),
          valid.kid(),
          valid.publicKeyFingerprint()),
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          valid.environmentId(),
          "cluster-b",
          valid.namespace(),
          valid.operationId(),
          valid.generation(),
          valid.kid(),
          valid.publicKeyFingerprint()),
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          valid.environmentId(),
          valid.clusterId(),
          "other",
          valid.operationId(),
          valid.generation(),
          valid.kid(),
          valid.publicKeyFingerprint()),
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          valid.environmentId(),
          valid.clusterId(),
          valid.namespace(),
          "1f1ce3e0-29f4-45a8-a5ed-b1a9e2dc087a",
          valid.generation(),
          valid.kid(),
          valid.publicKeyFingerprint()),
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          valid.environmentId(),
          valid.clusterId(),
          valid.namespace(),
          valid.operationId(),
          "43",
          valid.kid(),
          valid.publicKeyFingerprint()),
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          valid.environmentId(),
          valid.clusterId(),
          valid.namespace(),
          valid.operationId(),
          valid.generation(),
          "another-kid",
          valid.publicKeyFingerprint()),
      new AccountMountedJwtSignerBundle.ExpectedIdentity(
          valid.environmentId(),
          valid.clusterId(),
          valid.namespace(),
          valid.operationId(),
          valid.generation(),
          valid.kid(),
          "0".repeat(64))
    };

    for (AccountMountedJwtSignerBundle.ExpectedIdentity mismatch : mismatches) {
      AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException failure =
          assertThrows(
              AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
              () -> observe(mismatch));
      assertFalse(failure.getMessage().contains(privateMount.toString()));
      assertFalse(failure.getMessage().contains(privateKeyText(rsa3072)));
    }
  }

  @Test
  void rejectsUnknownNullDuplicateAndUnsupportedPrivateBundleFields() throws Exception {
    String valid = bundleJson(identity(), rsa3072);
    assertInvalidPrivateJson(valid.substring(0, valid.length() - 1) + ",\"unexpected\":true}");
    assertInvalidPrivateJson(valid.replace("\"namespace\":\"firemud\"", "\"namespace\":null"));
    assertInvalidPrivateJson(valid.replace("\"algorithm\":\"RS256\"", "\"algorithm\":\"HS256\""));
    assertInvalidPrivateJson(
        valid.replace(
            "\"algorithm\":\"RS256\"", "\"algorithm\":\"RS256\",\"algorithm\":\"RS256\""));
    assertInvalidPrivateJson(valid.replace("\"privateKeyPkcs8\":\"", "\"privateKeyPkcs8\":\"%%%"));
    String encodedPrivateKey = privateKeyText(rsa3072);
    assertInvalidPrivateJson(
        valid.replace(
            "\"privateKeyPkcs8\":\"" + encodedPrivateKey + "\"",
            "\"privateKeyPkcs8\":\"" + encodedPrivateKey + "=\""));
    assertInvalidPrivateJson(valid.substring(0, valid.length() - 2));

    byte[] malformedUtf8 = {(byte) 0xc3, (byte) 0x28};
    Files.write(privateMount.resolve("current.key"), malformedUtf8);
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () -> observe(identity()));
  }

  @Test
  void rejectsMalformedMissingAlteredDuplicateAndPrivateJwksEntries() throws Exception {
    String validJwk = jwk(identity().kid(), (RSAPublicKey) rsa3072.getPublic());
    Files.writeString(publicMount.resolve("jwks.json"), "{\"keys\":[]}");
    assertInvalidMountedPair();

    Files.writeString(
        publicMount.resolve("jwks.json"),
        "{\"keys\":[" + jwk("other-kid", (RSAPublicKey) rsa3072.getPublic()) + "]}");
    assertInvalidMountedPair();

    RSAPublicKey publicKey = (RSAPublicKey) rsa3072.getPublic();
    String changedModulus = base64Url(unsigned(publicKey.getModulus().subtract(BigInteger.TWO)));
    String altered =
        jwk(identity().kid(), publicKey)
            .replace(
                "\"n\":\"" + base64Url(unsigned(publicKey.getModulus())) + "\"",
                "\"n\":\"" + changedModulus + "\"");
    Files.writeString(publicMount.resolve("jwks.json"), "{\"keys\":[" + altered + "]}");
    assertInvalidMountedPair();

    Files.writeString(
        publicMount.resolve("jwks.json"), "{\"keys\":[" + validJwk + "," + validJwk + "]}");
    assertInvalidMountedPair();

    Files.writeString(
        publicMount.resolve("jwks.json"),
        "{\"keys\":[" + validJwk.replace("}", ",\"d\":\"AQAB\"}") + "]}");
    assertInvalidMountedPair();

    Files.delete(publicMount.resolve("jwks.json"));
    assertInvalidMountedPair();
  }

  @Test
  void rejectsWeakNonCrtAndNonRsaPrivateKeys() {
    WeakRsaCrtPrivateKey weakKey = new WeakRsaCrtPrivateKey();
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () -> AccountMountedJwtSignerBundle.validateStrongRsaCrtPrivateKey(weakKey));

    NonCrtRsaPrivateKey nonCrtKey = new NonCrtRsaPrivateKey();
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () -> AccountMountedJwtSignerBundle.validateStrongRsaCrtPrivateKey(nonCrtKey));

    PrivateKey nonRsaKey =
        new PrivateKey() {
          @Override
          public String getAlgorithm() {
            return "EC";
          }

          @Override
          public String getFormat() {
            return "PKCS#8";
          }

          @Override
          public byte[] getEncoded() {
            return new byte[0];
          }
        };
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () -> AccountMountedJwtSignerBundle.validateStrongRsaCrtPrivateKey(nonRsaKey));
  }

  @Test
  void acceptsProjectedSymlinksOnlyInsideTheExplicitPrivateMountRoot() throws Exception {
    Path projectedMount = Files.createDirectory(tempDirectory.resolve("projected-private"));
    Path versionDirectory = Files.createDirectory(projectedMount.resolve("..2026_10_05_00_00_00"));
    Files.writeString(versionDirectory.resolve("current.key"), bundleJson(identity(), rsa3072));
    try {
      Files.createSymbolicLink(projectedMount.resolve("..data"), versionDirectory.getFileName());
      Files.createSymbolicLink(
          projectedMount.resolve("current.key"), Path.of("..data", "current.key"));
    } catch (UnsupportedOperationException | java.io.IOException ex) {
      Assumptions.assumeTrue(false, "Filesystem does not support projected-volume symlinks");
    }

    AccountMountedJwtSignerBundle.LocalObservation observation =
        AccountMountedJwtSignerBundle.observeMounted(
            projectedMount, Path.of("current.key"), publicMount, Path.of("jwks.json"), identity());
    assertTrue(observation.localPrivatePublicCorrespondenceObserved());

    Path outside = Files.createDirectory(tempDirectory.resolve("outside-private"));
    Files.writeString(outside.resolve("current.key"), bundleJson(identity(), rsa3072));
    Path escapingMount = Files.createDirectory(tempDirectory.resolve("escaping-private"));
    try {
      Files.createSymbolicLink(
          escapingMount.resolve("current.key"), outside.resolve("current.key"));
    } catch (UnsupportedOperationException | java.io.IOException ex) {
      Assumptions.assumeTrue(false, "Filesystem does not support symlink validation");
    }
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () ->
            AccountMountedJwtSignerBundle.observeMounted(
                escapingMount,
                Path.of("current.key"),
                publicMount,
                Path.of("jwks.json"),
                identity()));

    Path outsideJwks = outside.resolve("jwks.json");
    Files.writeString(outsideJwks, jwksJson(identity(), rsa3072));
    Files.delete(publicMount.resolve("jwks.json"));
    try {
      Files.createSymbolicLink(publicMount.resolve("jwks.json"), outsideJwks);
    } catch (UnsupportedOperationException | java.io.IOException ex) {
      Assumptions.assumeTrue(false, "Filesystem does not support public-projection symlink checks");
    }
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () -> observe(identity()));
  }

  @Test
  void rejectsInvalidCanonicalIdentityEncodingsAndPartialProjectionFiles() throws Exception {
    AccountMountedJwtSignerBundle.ExpectedIdentity malformedIdentity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            ENVIRONMENT,
            CLUSTER,
            NAMESPACE,
            "7AF097EA-B1D1-42EA-9F24-46AEB211A779",
            GENERATION,
            KID,
            identity().publicKeyFingerprint());
    assertThrows(
        AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
        () -> observe(malformedIdentity));

    Files.writeString(
        privateMount.resolve("current.key"),
        bundleJson(identity(), rsa3072).replace("\"generation\":\"42\"", "\"generation\":\"042\""));
    assertInvalidMountedPair();

    Files.writeString(publicMount.resolve("jwks.json"), "{\"keys\":[");
    assertInvalidMountedPair();
  }

  @Test
  void rejectsOversizedPrivateAndPublicProjectionFiles() throws Exception {
    Files.write(
        privateMount.resolve("current.key"),
        new byte[AccountMountedJwtSignerBundle.MAX_PRIVATE_BUNDLE_BYTES + 1]);
    assertInvalidMountedPair();

    Files.writeString(privateMount.resolve("current.key"), bundleJson(identity(), rsa3072));
    Files.write(
        publicMount.resolve("jwks.json"),
        new byte[AccountMountedJwtSignerBundle.MAX_PUBLIC_JWKS_BYTES + 1]);
    assertInvalidMountedPair();
  }

  private AccountMountedJwtSignerBundle.LocalObservation observe(
      AccountMountedJwtSignerBundle.ExpectedIdentity expected) {
    return AccountMountedJwtSignerBundle.observeMounted(
        privateMount, Path.of("current.key"), publicMount, Path.of("jwks.json"), expected);
  }

  private void assertInvalidPrivateJson(String json) throws Exception {
    Files.writeString(privateMount.resolve("current.key"), json);
    assertInvalidMountedPair();
  }

  private void assertInvalidMountedPair() {
    AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException failure =
        assertThrows(
            AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class,
            () -> observe(identity()));
    assertFalse(failure.getMessage().contains(privateMount.toString()));
    assertFalse(failure.getMessage().contains(privateKeyText(rsa3072)));
  }

  private static AccountMountedJwtSignerBundle.ExpectedIdentity identity() {
    return new AccountMountedJwtSignerBundle.ExpectedIdentity(
        ENVIRONMENT, CLUSTER, NAMESPACE, OPERATION, GENERATION, KID, fingerprint(rsa3072));
  }

  private static String bundleJson(
      AccountMountedJwtSignerBundle.ExpectedIdentity identity, KeyPair pair) {
    return "{"
        + "\"version\":1,"
        + "\"environmentId\":\""
        + identity.environmentId()
        + "\","
        + "\"clusterId\":\""
        + identity.clusterId()
        + "\","
        + "\"namespace\":\""
        + identity.namespace()
        + "\","
        + "\"operationId\":\""
        + identity.operationId()
        + "\","
        + "\"generation\":\""
        + identity.generation()
        + "\","
        + "\"kid\":\""
        + identity.kid()
        + "\","
        + "\"algorithm\":\"RS256\","
        + "\"privateKeyPkcs8\":\""
        + privateKeyText(pair)
        + "\","
        + "\"publicKeyFingerprint\":\""
        + identity.publicKeyFingerprint()
        + "\""
        + "}";
  }

  private static String jwksJson(
      AccountMountedJwtSignerBundle.ExpectedIdentity identity, KeyPair pair) {
    return "{\"keys\":[" + jwk(identity.kid(), (RSAPublicKey) pair.getPublic()) + "]}";
  }

  private static String jwk(String kid, RSAPublicKey key) {
    return "{"
        + "\"kty\":\"RSA\","
        + "\"use\":\"sig\","
        + "\"alg\":\"RS256\","
        + "\"kid\":\""
        + kid
        + "\","
        + "\"n\":\""
        + base64Url(unsigned(key.getModulus()))
        + "\","
        + "\"e\":\""
        + base64Url(unsigned(key.getPublicExponent()))
        + "\""
        + "}";
  }

  private static String fingerprint(KeyPair pair) {
    RSAPublicKey key = (RSAPublicKey) pair.getPublic();
    String canonicalJwk =
        "{\"e\":\""
            + base64Url(unsigned(key.getPublicExponent()))
            + "\",\"kty\":\"RSA\",\"n\":\""
            + base64Url(unsigned(key.getModulus()))
            + "\"}";
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(canonicalJwk.getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String privateKeyText(KeyPair pair) {
    byte[] encoded = pair.getPrivate().getEncoded();
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
    } finally {
      Arrays.fill(encoded, (byte) 0);
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] encoded = value.toByteArray();
    return encoded.length > 1 && encoded[0] == 0
        ? Arrays.copyOfRange(encoded, 1, encoded.length)
        : encoded;
  }

  private static String base64Url(byte[] value) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    } finally {
      Arrays.fill(value, (byte) 0);
    }
  }

  private static boolean isAscii(byte[] value) {
    for (byte octet : value) {
      if (octet < 0x21 || octet > 0x7e) {
        return false;
      }
    }
    return true;
  }

  private static final class WeakRsaCrtPrivateKey implements RSAPrivateCrtKey {
    @Override
    public BigInteger getModulus() {
      return BigInteger.ONE.shiftLeft(2_047).setBit(0);
    }

    @Override
    public BigInteger getPublicExponent() {
      return BigInteger.valueOf(65_537);
    }

    @Override
    public BigInteger getPrivateExponent() {
      return BigInteger.valueOf(3);
    }

    @Override
    public BigInteger getPrimeP() {
      return BigInteger.valueOf(3);
    }

    @Override
    public BigInteger getPrimeQ() {
      return BigInteger.valueOf(5);
    }

    @Override
    public BigInteger getPrimeExponentP() {
      return BigInteger.ONE;
    }

    @Override
    public BigInteger getPrimeExponentQ() {
      return BigInteger.ONE;
    }

    @Override
    public BigInteger getCrtCoefficient() {
      return BigInteger.ONE;
    }

    @Override
    public String getAlgorithm() {
      return "RSA";
    }

    @Override
    public String getFormat() {
      return "PKCS#8";
    }

    @Override
    public byte[] getEncoded() {
      return new byte[0];
    }
  }

  private static final class NonCrtRsaPrivateKey implements RSAPrivateKey {
    @Override
    public BigInteger getModulus() {
      return BigInteger.ONE.shiftLeft(3_071).setBit(0);
    }

    @Override
    public BigInteger getPrivateExponent() {
      return BigInteger.valueOf(3);
    }

    @Override
    public String getAlgorithm() {
      return "RSA";
    }

    @Override
    public String getFormat() {
      return "PKCS#8";
    }

    @Override
    public byte[] getEncoded() {
      return new byte[0];
    }
  }
}
