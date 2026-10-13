package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountJwtPodReceiverIdentity;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPodReceiverWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPodReceiverWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.SignerFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodLocalIdentityProvider.LocalObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.PodTarget;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtReadinessPodReceiverEngineTest {
  private static final Instant NOW = Instant.parse("2030-06-01T00:10:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final String ACTIVE_KID = "active-key-6";
  private static final String PENDING_KID = "pending-key-7";
  private static final String MATRIX_DIGEST = "c".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void
      verifiesAllAccountProfilesWithProductionCryptoAfterUnknownPendingKidRefreshAndOnlyReadsOwner()
          throws Exception {
    Fixture fixture = new Fixture();
    Map<String, String> profiles =
        Map.of(
            "account-jwt-readiness-canary",
            "firemud-account-jwt-readiness",
            ControlUiJwtProfileValidator.PROFILE,
            ControlUiJwtProfileValidator.AUDIENCE,
            PlayerBootstrapJwtProfileValidator.PROFILE,
            PlayerBootstrapJwtProfileValidator.AUDIENCE,
            GameSessionAccountDelegationProfile.PROFILE,
            GameSessionAccountDelegationProfile.AUDIENCE);

    for (Map.Entry<String, String> profile : profiles.entrySet()) {
      ReceiveAccountJwtReadinessProbeRequest request =
          fixture.request(profile.getKey(), profile.getValue());
      var response = fixture.engine.receiveReadinessProbe(request);
      assertThat(response.getSchemaVersion()).isEqualTo(1);
      assertThat(response.getCoordinates()).isEqualTo(request.getCoordinates());
      assertThat(response.getCompactTokenSha256()).matches("[0-9a-f]{64}");
      assertThat(response.getVerifiedKeyId()).isEqualTo(PENDING_KID);
      assertThat(response.getOutcome())
          .isEqualTo(
              net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeResponse
                  .ObservationOutcome.VERIFIED);
      assertThat(response.getReceiverIdentity().getPodUid()).isEqualTo(Fixture.POD_UID);
      assertThat(response.getReceiverIdentity().getJwksTrustBindingRevision())
          .isEqualTo("account-api-r1");
      assertThat(response.getAccountPodTarget().getPodUid()).isEqualTo(Fixture.POD_UID);
      assertThat(response.getObservedAtEpochSeconds()).isEqualTo(NOW.getEpochSecond());
    }

    verify(fixture.localIdentityProvider, times(2 * profiles.size()))
        .observe(MATRIX_DIGEST, fixture.observationContext);
    verify(fixture.probeService, times(2 * profiles.size()))
        .readCurrentProbeOwner(
            eq(fixture.accountBinding.accountBinding()),
            any(TrustFence.class),
            any(AccountJwtReadinessProbeOwnerSelector.class),
            eq("firemud-prod"));
    verify(fixture.probeService, times(profiles.size()))
        .readCurrentPlan(
            eq(fixture.accountBinding.accountBinding()), any(TrustFence.class), any(UUID.class));
    verify(fixture.probeService, times(profiles.size()))
        .readCurrentInventoryObservationContext(
            eq(fixture.accountBinding.accountBinding()), any(TrustFence.class), any(UUID.class));
    verifyNoMoreInteractions(fixture.probeService);
    verify(fixture.workloadGuard, times(profiles.size())).requireUnchanged(fixture.caller);
  }

  @Test
  void signatureFailureDoesNotProduceReceiptOrAfterCryptoIdentityRead() throws Exception {
    Fixture fixture = new Fixture();
    ReceiveAccountJwtReadinessProbeRequest valid =
        fixture.request(
            ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE);
    String[] parts = valid.getCompactJwt().split("\\.", -1);
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 0x01;
    String tampered = parts[0] + "." + parts[1] + "." + encode(signature);

    assertThatThrownBy(
            () ->
                fixture.engine.receiveReadinessProbe(
                    valid.toBuilder().setCompactJwt(tampered).build()))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);

    InOrder reads = inOrder(fixture.probeService, fixture.localIdentityProvider);
    reads
        .verify(fixture.probeService)
        .readCurrentPlan(
            eq(fixture.accountBinding.accountBinding()),
            any(TrustFence.class),
            eq(fixture.operationId));
    reads
        .verify(fixture.probeService)
        .readCurrentInventoryObservationContext(
            eq(fixture.accountBinding.accountBinding()),
            any(TrustFence.class),
            eq(fixture.operationId));
    reads.verify(fixture.localIdentityProvider).observe(MATRIX_DIGEST, fixture.observationContext);
    reads
        .verify(fixture.probeService)
        .readCurrentProbeOwner(
            eq(fixture.accountBinding.accountBinding()),
            any(TrustFence.class),
            any(AccountJwtReadinessProbeOwnerSelector.class),
            eq("firemud-prod"));
    verifyNoMoreInteractions(fixture.probeService);
    verifyNoMoreInteractions(fixture.localIdentityProvider);
  }

  @Test
  void absentPersistedPlanFailsBeforeIdentityOrReceiverOwnerRead() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.probeService.readCurrentPlan(
            eq(fixture.accountBinding.accountBinding()), any(TrustFence.class), any(UUID.class)))
        .thenThrow(new AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException());
    ReceiveAccountJwtReadinessProbeRequest request =
        fixture.request(
            ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE);

    assertThatThrownBy(() -> fixture.engine.receiveReadinessProbe(request))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);

    verify(fixture.localIdentityProvider, never()).observe(any(), any());
    verify(fixture.probeService).readCurrentPlan(any(), any(), any());
    verify(fixture.probeService, never())
        .readCurrentInventoryObservationContext(any(), any(), any());
    verify(fixture.probeService, never()).readCurrentProbeOwner(any(), any(), any(), any());
    verifyNoMoreInteractions(fixture.probeService);
  }

  @Test
  void ownerOrLocalIdentityDriftAfterCryptoRejectsWithoutSuccess() throws Exception {
    Fixture ownerDrift = new Fixture();
    OwnerProbeEvidence changedEvidence = mock(OwnerProbeEvidence.class);
    when(ownerDrift.probeService.readCurrentProbeOwner(any(), any(), any(), any()))
        .thenReturn(ownerDrift.ownerEvidence, changedEvidence);
    var request =
        ownerDrift.request(
            ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE);
    assertThatThrownBy(() -> ownerDrift.engine.receiveReadinessProbe(request))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);

    Fixture localDrift = new Fixture();
    AccountJwtPodReceiverIdentity changedWireIdentity =
        localDrift.localObservation.wireIdentity().toBuilder().setPodIp("10.0.0.13").build();
    when(localDrift.localIdentityProvider.observe(MATRIX_DIGEST, localDrift.observationContext))
        .thenReturn(
            localDrift.localObservation,
            new LocalObservation(
                localDrift.localObservation.selectorIdentity(), changedWireIdentity));
    assertThatThrownBy(
            () ->
                localDrift.engine.receiveReadinessProbe(
                    localDrift.request(
                        ControlUiJwtProfileValidator.PROFILE,
                        ControlUiJwtProfileValidator.AUDIENCE)))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
  }

  private static final class Fixture {
    private final UUID operationId = UUID.randomUUID();
    private final UUID jti = UUID.randomUUID();
    private static final String POD_UID = "33333333-3333-4333-8333-333333333333";
    private static final String DEPLOYMENT_UID = "44444444-4444-4444-8444-444444444444";
    private final AccountJwtSignerMaterializerTrustBinding.Binding accountBinding =
        accountBinding();
    private final AuthenticatedCaller caller =
        new AuthenticatedCaller(accountBinding, apiBinding());
    private final AccountJwtReadinessProbeService probeService =
        mock(AccountJwtReadinessProbeService.class);
    private final AccountJwtReadinessPodReceiverWorkloadGuard workloadGuard =
        mock(AccountJwtReadinessPodReceiverWorkloadGuard.class);
    private final AccountJwtReadinessPodLocalIdentityProvider localIdentityProvider =
        mock(AccountJwtReadinessPodLocalIdentityProvider.class);
    private final ReadinessProbePlan plan = mock(ReadinessProbePlan.class);
    private final ProbeEntry entry = mock(ProbeEntry.class);
    private final OwnerProbeEvidence ownerEvidence = mock(OwnerProbeEvidence.class);
    private final AccountJwtReadinessProbeOwnerSelector.LocalIdentity selectorIdentity =
        localIdentity();
    private final LocalObservation localObservation =
        new LocalObservation(selectorIdentity, wireIdentity());
    private final ObservationContext observationContext =
        new ObservationContext(
            ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES, operationId, "a".repeat(64));
    private final KeyPair pendingKey;
    private final AccountAsymmetricJwtVerifier verifier;
    private final AccountJwtReadinessPodReceiverEngine engine;

    private Fixture() throws Exception {
      KeyPair active = rsa3072();
      pendingKey = rsa3072();
      AtomicReference<String> jwks =
          new AtomicReference<>(jwks(ACTIVE_KID, (RSAPublicKey) active.getPublic()));
      AccountPublicJwksCache.SourceIdentity sourceIdentity = sourceIdentity();
      AccountPublicJwksCache cache =
          new AccountPublicJwksCache(
              () ->
                  new AccountPublicJwksCache.PublicJwksSnapshot(
                      sourceIdentity, jwks.get().getBytes(StandardCharsets.US_ASCII)),
              sourceIdentity,
              CLOCK,
              Duration.ofSeconds(300));
      cache.keyFor(ACTIVE_KID);
      jwks.set(
          jwks(
              ACTIVE_KID,
              (RSAPublicKey) active.getPublic(),
              PENDING_KID,
              (RSAPublicKey) pendingKey.getPublic()));
      verifier = new AccountAsymmetricJwtVerifier(cache, CLOCK);

      when(workloadGuard.requireAccountReceiverCaller()).thenReturn(caller);
      when(localIdentityProvider.observe(MATRIX_DIGEST, observationContext))
          .thenReturn(localObservation, localObservation);
      when(plan.operationId()).thenReturn(operationId);
      when(plan.operationDigest()).thenReturn("a".repeat(64));
      when(plan.planDigest()).thenReturn("b".repeat(64));
      when(plan.planVersion()).thenReturn(2);
      when(plan.expiresAtEpochSecond()).thenReturn(NOW.getEpochSecond() + 300);
      when(plan.targetGeneration()).thenReturn("7");
      when(plan.targetKid()).thenReturn(PENDING_KID);
      when(plan.expectedFence()).thenReturn(new SignerFence(Optional.empty(), Optional.empty()));
      when(plan.validatorInventoryComplete()).thenReturn(true);
      when(plan.applicabilityMatrixDigest()).thenReturn(MATRIX_DIGEST);
      when(entry.targetKid()).thenReturn(PENDING_KID);
      when(entry.plannedIssuedAtEpochSecond()).thenReturn(NOW.getEpochSecond() - 1);
      when(entry.expiresAtEpochSecond()).thenReturn(NOW.getEpochSecond() + 299);
      when(ownerEvidence.plan()).thenReturn(plan);
      when(ownerEvidence.entry()).thenReturn(entry);
      var expectedPod = mock(AccountJwtReadinessProbeRepository.ExpectedPod.class);
      when(expectedPod.target()).thenReturn(podTarget());
      when(ownerEvidence.expectedPod()).thenReturn(expectedPod);
      when(probeService.readCurrentPlan(
              eq(accountBinding.accountBinding()), any(TrustFence.class), any(UUID.class)))
          .thenReturn(plan);
      when(probeService.readCurrentInventoryObservationContext(
              eq(accountBinding.accountBinding()), any(TrustFence.class), eq(operationId)))
          .thenReturn(observationContext);
      when(probeService.readCurrentProbeOwner(any(), any(), any(), any()))
          .thenReturn(ownerEvidence);

      engine =
          new AccountJwtReadinessPodReceiverEngine(
              probeService,
              workloadGuard,
              localIdentityProvider,
              new AccountJwtReadinessPodReceiverProtoMapper(),
              verifier,
              CLOCK,
              8);
    }

    private ReceiveAccountJwtReadinessProbeRequest request(String profile, String audience)
        throws Exception {
      boolean canary = "account-jwt-readiness-canary".equals(profile);
      String compactJwt = token(profile, audience, canary, jti, PENDING_KID, pendingKey());
      ReadinessProbeCoordinates coordinates =
          ReadinessProbeCoordinates.newBuilder()
              .setRotationOperationId(operationId.toString())
              .setOperationDigest("a".repeat(64))
              .setPlanDigest("b".repeat(64))
              .setPlanVersion(2)
              .setRegistryVersion(1)
              .setValidatorId("account-service")
              .setTokenProfile(profile)
              .setAudience(audience)
              .setProbeKind(
                  canary
                      ? ReadinessProbeCoordinates.ProbeKind.CANARY
                      : ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
              .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
              .setJti(jti.toString())
              .setEntryVersion(4)
              .setTargetGeneration("7")
              .setTargetKid(PENDING_KID)
              .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
              .setIssuedAtEpochSeconds(NOW.getEpochSecond() - 1)
              .setExpiresAtEpochSeconds(NOW.getEpochSecond() + 299)
              .setPlanExpiresAtEpochSeconds(NOW.getEpochSecond() + 300)
              .build();
      return ReceiveAccountJwtReadinessProbeRequest.newBuilder()
          .setSchemaVersion(1)
          .setCoordinates(coordinates)
          .setCompactJwt(compactJwt)
          .build();
    }

    private KeyPair pendingKey() {
      // The trusted source serves only public keys; this is a signing-only fixture key.
      return pendingKey;
    }

    private AccountJwtReadinessProbeOwnerSelector.LocalIdentity localIdentity() {
      return new AccountJwtReadinessProbeOwnerSelector.LocalIdentity(
          "account-service",
          DEPLOYMENT_UID,
          POD_UID,
          "10.0.0.12",
          "grpcs://10.0.0.12:6565",
          "spiffe://firemud/ns/firemud-prod/sa/account-service",
          "registry.example/account@sha256:" + "a".repeat(64),
          "b".repeat(64),
          MATRIX_DIGEST,
          "inventory-r1",
          "d".repeat(64),
          "e".repeat(64));
    }

    private AccountJwtPodReceiverIdentity wireIdentity() {
      return AccountJwtPodReceiverIdentity.newBuilder()
          .setValidatorId("account-service")
          .setDeploymentUid(DEPLOYMENT_UID)
          .setPodUid(POD_UID)
          .setPodIp("10.0.0.12")
          .setDirectPodEndpoint("grpcs://10.0.0.12:6565")
          .setCanonicalServiceUri("spiffe://firemud/ns/firemud-prod/sa/account-service")
          .setImage("registry.example/account@sha256:" + "a".repeat(64))
          .setVerifierConfigSha256("b".repeat(64))
          .setApplicabilityMatrixDigest(MATRIX_DIGEST)
          .setSourceInventoryRevision("inventory-r1")
          .setSourceInventoryDigest("d".repeat(64))
          .setServerLeafSpkiSha256("e".repeat(64))
          .setAccountJwksSourceIdentity(
              AccountSourceIdentity.newBuilder()
                  .setEnvironmentId("prod")
                  .setClusterId("cluster-a")
                  .setClusterIncarnationUid("11111111-1111-4111-8111-111111111111")
                  .setNamespace("firemud-prod")
                  .setNamespaceUid("22222222-2222-4222-8222-222222222222")
                  .setConfigMapUid("55555555-5555-4555-8555-555555555555")
                  .setBindingRevision("account-api-r1")
                  .setApiServerOrigin("https://kubernetes.example.test:6443")
                  .setServingCaSha256("9".repeat(64)))
          .setJwksTrustBindingRevision("account-api-r1")
          .build();
    }

    private PodTarget podTarget() {
      return new PodTarget(
          "f".repeat(64),
          "prod",
          "cluster-a",
          "11111111-1111-4111-8111-111111111111",
          "firemud-prod",
          "22222222-2222-4222-8222-222222222222",
          "api-r1",
          "8".repeat(64),
          "inventory-r1",
          "d".repeat(64),
          "account-service",
          DEPLOYMENT_UID,
          POD_UID,
          "10.0.0.12",
          "registry.example/account@sha256:" + "a".repeat(64),
          "b".repeat(64),
          MATRIX_DIGEST,
          ProbeExpectation.ACCEPT,
          Optional.of(URI.create("grpcs://10.0.0.12:6565")),
          Optional.of("spiffe://firemud/ns/firemud-prod/sa/account-service"),
          Optional.of("e".repeat(64)));
    }
  }

  private static AccountJwtSignerMaterializerTrustBinding.Binding accountBinding() {
    String namespace = "firemud-prod";
    String revision = "trust-r1";
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/jwt-signer-materializer";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision, "prod", "cluster-a", namespace, clusterUid, namespaceUid, peer, pins);
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        "prod", "cluster-a", namespace, clusterUid, namespaceUid, peer, pins, revision, digest);
  }

  private static AccountJwtJwksApiBinding.ParsedBinding apiBinding() {
    return new AccountJwtJwksApiBinding.ParsedBinding(
        "api-r1",
        "prod",
        "cluster-a",
        URI.create("https://kubernetes.example.test:6443"),
        "kubernetes.example.test",
        fixtureOnlyPath("serving-ca.pem"),
        "9".repeat(64),
        fixtureOnlyPath("bearer-token"),
        "firemud-prod",
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "system:serviceaccount:firemud-prod:account-service",
        "7".repeat(64));
  }

  private static Path fixtureOnlyPath(String name) {
    // The guard fixture carries these parsed paths as identity only and never opens them.
    return Path.of("protected").toAbsolutePath().resolve(name);
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

  private static String token(
      String profile, String audience, boolean canary, UUID jti, String kid, KeyPair keyPair)
      throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", kid, "typ", "JWT");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", RESERVED_SUBJECT);
    claims.put("jti", jti.toString());
    claims.put("aud", audience);
    claims.put("iat", NOW.getEpochSecond() - 1L);
    claims.put("nbf", NOW.getEpochSecond() - 1L);
    claims.put("exp", NOW.getEpochSecond() + 299L);
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
      Object initialCounter = decimalCounters ? "1" : 1L;
      claims.put("tokenGeneration", initialCounter);
      claims.put("authorityTuple", authorityTuple(initialCounter));
      claims.put("membershipVersion", Map.of());
      claims.put("issuanceFence", initialCounter);
    }
    String signingInput =
        encode(JSON.writeValueAsBytes(header))
            + "."
            + encode(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(claims)));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyPair.getPrivate());
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

  private static String jwks(String kid, RSAPublicKey key) {
    return jwksEntries(Map.of(kid, key));
  }

  private static String jwks(
      String firstKid, RSAPublicKey firstKey, String secondKid, RSAPublicKey secondKey) {
    return jwksEntries(Map.of(firstKid, firstKey, secondKid, secondKey));
  }

  private static String jwksEntries(Map<String, RSAPublicKey> keys) {
    StringBuilder json = new StringBuilder("{\"keys\":[");
    boolean first = true;
    for (Map.Entry<String, RSAPublicKey> entry : keys.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      RSAPublicKey key = entry.getValue();
      json.append("{\"kty\":\"RSA\",\"kid\":\"")
          .append(entry.getKey())
          .append("\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"")
          .append(encode(unsigned(key.getModulus())))
          .append("\",\"e\":\"")
          .append(encode(unsigned(key.getPublicExponent())))
          .append("\"}");
    }
    return json.append("]}").toString();
  }

  private static KeyPair rsa3072() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(new RSAKeyGenParameterSpec(3_072, RSAKeyGenParameterSpec.F4));
      KeyPair key = generator.generateKeyPair();
      return key;
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return bytes;
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
