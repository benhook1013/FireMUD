package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.config.GameSessionJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider.LocalObservation;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class GameSessionJwtReadinessReceiverEngineTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String METHOD = GameSessionJwtReadinessReceiverEngine.FULL_METHOD_NAME;
  private static final String KID = "pending-key-7";
  private static final Instant NOW = Instant.parse("2030-06-01T00:10:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void ownerReadsBracketRealBoundaryAndReturnOnlyNonAuthorizingReceipt() {
    Fixture fixture = new Fixture();
    fixture.stubVerifiedOutcome(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED);

    ReceiveReadinessProbeResponse response =
        withAccountPeer(() -> fixture.engine.receiveReadinessProbe(fixture.request));

    assertThat(response.getSchemaVersion()).isEqualTo(1);
    assertThat(response.getCoordinates()).isEqualTo(fixture.request.getCoordinates());
    assertThat(response.getCompactTokenSha256()).matches("[0-9a-f]{64}");
    assertThat(response.getVerifiedKeyId()).isEqualTo(KID);
    assertThat(response.getReceiverIdentity()).isEqualTo(fixture.local.wireIdentity());
    assertThat(response.getAccountPodTarget()).isEqualTo(fixture.target);
    assertThat(response.getObservedAtEpochSeconds()).isEqualTo(NOW.getEpochSecond());
    assertThat(response.getOutcome())
        .isEqualTo(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED);
    assertThat(SessionContext.getAccountId()).isNull();
    assertThat(SessionContext.getGlobalRoles()).isEmpty();

    ArgumentCaptor<GetCurrentReadinessProbeOwnerRequest> ownerRequest =
        ArgumentCaptor.forClass(GetCurrentReadinessProbeOwnerRequest.class);
    InOrder order = inOrder(fixture.localIdentityProvider, fixture.ownerReadPort, fixture.crypto);
    order.verify(fixture.localIdentityProvider).observe();
    order.verify(fixture.ownerReadPort).readCurrent(ownerRequest.capture());
    order.verify(fixture.crypto).verify(any());
    order.verify(fixture.localIdentityProvider).observe();
    order.verify(fixture.ownerReadPort).readCurrent(ownerRequest.capture());
    verify(fixture.localIdentityProvider, times(2)).observe();
    verify(fixture.ownerReadPort, times(2)).readCurrent(any());
    assertThat(ownerRequest.getAllValues()).hasSize(2);
    for (GetCurrentReadinessProbeOwnerRequest captured : ownerRequest.getAllValues()) {
      assertThat(captured.getExpectedCoordinates()).isEqualTo(fixture.request.getCoordinates());
      assertThat(captured.getCompactTokenSha256()).isEqualTo(response.getCompactTokenSha256());
      assertThat(captured.getProtectedLocalIdentity()).isEqualTo(fixture.local.wireIdentity());
      assertThat(captured.getUnknownFields().asMap()).isEmpty();
    }
    verifyNoMoreInteractions(fixture.localIdentityProvider, fixture.ownerReadPort, fixture.crypto);
  }

  @Test
  void cryptographicOutcomeMismatchDeniesWithoutSecondOwnerReadOrReceipt() {
    Fixture fixture = new Fixture();
    fixture.stubVerifiedOutcome(
        ReceiveReadinessProbeResponse.ObservationOutcome.INAPPLICABLE_REJECT);

    assertThatThrownBy(
            () -> withAccountPeer(() -> fixture.engine.receiveReadinessProbe(fixture.request)))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);

    verify(fixture.ownerReadPort, times(1)).readCurrent(any());
    verify(fixture.localIdentityProvider, times(1)).observe();
    verify(fixture.crypto).verify(any());
    verifyNoMoreInteractions(fixture.localIdentityProvider, fixture.ownerReadPort, fixture.crypto);
  }

  @Test
  void staleCurrentOwnerReadbackDeniesAfterCrypto() {
    Fixture fixture = new Fixture();
    fixture.stubVerifiedOutcome(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED);
    AccountPodTargetBinding changedTarget =
        fixture.target.toBuilder().setPodIp("10.0.0.99").build();
    when(fixture.ownerReadPort.readCurrent(any()))
        .thenReturn(
            fixture.ownerResponse,
            fixture.ownerResponse.toBuilder().setCurrentPodTarget(changedTarget).build());

    assertThatThrownBy(
            () -> withAccountPeer(() -> fixture.engine.receiveReadinessProbe(fixture.request)))
        .isInstanceOf(
            GameSessionJwtReadinessReceiverProtoMapper.OwnerEvidenceUnavailableException.class);

    verify(fixture.ownerReadPort, times(2)).readCurrent(any());
    verify(fixture.localIdentityProvider, times(2)).observe();
    verify(fixture.crypto).verify(any());
    verifyNoMoreInteractions(fixture.localIdentityProvider, fixture.ownerReadPort, fixture.crypto);
  }

  private static <T> T withAccountPeer(java.util.concurrent.Callable<T> action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
            NAMESPACE,
            "account-service");
    Context context = Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.call();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new AssertionError(failure);
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }
  }

  private static final class Fixture {
    private final UUID operationId = UUID.randomUUID();
    private final UUID jti = UUID.randomUUID();
    private final GameSessionJwtReadinessLocalIdentityProvider localIdentityProvider =
        mock(GameSessionJwtReadinessLocalIdentityProvider.class);
    private final GameSessionJwtReadinessProbeOwnerReadPort ownerReadPort =
        mock(GameSessionJwtReadinessProbeOwnerReadPort.class);
    private final GameSessionJwtReadinessProbeCrypto crypto =
        mock(GameSessionJwtReadinessProbeCrypto.class);
    private final GameSessionJwtReadinessReceiverProtoMapper mapper =
        new GameSessionJwtReadinessReceiverProtoMapper();
    private final AccountPublicJwksCache.SourceIdentity sourceIdentity = sourceIdentity();
    private final LocalObservation local = new LocalObservation(wireIdentity(), sourceIdentity);
    private final ReadinessProbeCoordinates coordinates = coordinates();
    private final ReceiveReadinessProbeRequest request =
        ReceiveReadinessProbeRequest.newBuilder()
            .setSchemaVersion(1)
            .setCoordinates(coordinates)
            .setCompactJwt("header.payload.signature")
            .build();
    private final AccountPodTargetBinding target = target();
    private final GetCurrentReadinessProbeOwnerResponse ownerResponse = ownerResponse();
    private final GameSessionJwtReadinessReceiverEngine engine =
        new GameSessionJwtReadinessReceiverEngine(
            new GameSessionJwtReadinessProbeOwnerWorkloadGuard(NAMESPACE),
            localIdentityProvider,
            ownerReadPort,
            mapper,
            crypto,
            CLOCK);

    private Fixture() {
      when(localIdentityProvider.observe()).thenReturn(local);
      when(ownerReadPort.readCurrent(any())).thenReturn(ownerResponse);
    }

    private void stubVerifiedOutcome(ReceiveReadinessProbeResponse.ObservationOutcome outcome) {
      when(crypto.verify(any()))
          .thenReturn(new GameSessionJwtReadinessProbeCrypto.VerifiedProbe(KID, outcome));
    }

    private ReadinessProbeCoordinates coordinates() {
      long issuedAt = NOW.getEpochSecond() - 1L;
      return ReadinessProbeCoordinates.newBuilder()
          .setRotationOperationId(operationId.toString())
          .setOperationDigest("a".repeat(64))
          .setPlanDigest("b".repeat(64))
          .setPlanVersion(2)
          .setRegistryVersion(1)
          .setValidatorId(GameSessionJwtReadinessReceiverProtoMapper.GAME_SESSION_VALIDATOR)
          .setTokenProfile("game-session-account-delegation")
          .setAudience("account-service")
          .setProbeKind(ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
          .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
          .setJti(jti.toString())
          .setEntryVersion(4)
          .setTargetGeneration("7")
          .setTargetKid(KID)
          .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
          .setIssuedAtEpochSeconds(issuedAt)
          .setExpiresAtEpochSeconds(issuedAt + 120L)
          .setPlanExpiresAtEpochSeconds(issuedAt + 180L)
          .build();
    }

    private static ReadinessReceiverLocalIdentity wireIdentity() {
      return ReadinessReceiverLocalIdentity.newBuilder()
          .setValidatorId("game-session-service")
          .setDeploymentUid("33333333-3333-4333-8333-333333333333")
          .setPodUid("44444444-4444-4444-8444-444444444444")
          .setPodIp("10.0.0.12")
          .setDirectPodEndpoint("grpcs://10.0.0.12:6565")
          .setCanonicalServiceUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service")
          .setImage("registry.example/game-session@sha256:" + "a".repeat(64))
          .setVerifierConfigSha256("b".repeat(64))
          .setApplicabilityMatrixDigest("c".repeat(64))
          .setSourceInventoryRevision("inventory-r1")
          .setSourceInventoryDigest("d".repeat(64))
          .setServerLeafSpkiSha256("e".repeat(64))
          .setAccountJwksSourceIdentity(
              AccountSourceIdentity.newBuilder()
                  .setEnvironmentId("prod")
                  .setClusterId("cluster-a")
                  .setClusterIncarnationUid("11111111-1111-4111-8111-111111111111")
                  .setNamespace(NAMESPACE)
                  .setNamespaceUid("22222222-2222-4222-8222-222222222222")
                  .setConfigMapUid("55555555-5555-4555-8555-555555555555")
                  .setBindingRevision("account-api-r1")
                  .setApiServerOrigin("https://kubernetes.example.test:6443")
                  .setServingCaSha256("9".repeat(64)))
          .setAccountJwksTrustConfigRevision(1L)
          .build();
    }

    private static AccountPublicJwksCache.SourceIdentity sourceIdentity() {
      return new AccountPublicJwksCache.SourceIdentity(
          "prod",
          "cluster-a",
          "11111111-1111-4111-8111-111111111111",
          NAMESPACE,
          "22222222-2222-4222-8222-222222222222",
          "55555555-5555-4555-8555-555555555555",
          "account-api-r1",
          "https://kubernetes.example.test:6443",
          "9".repeat(64));
    }

    private AccountPodTargetBinding target() {
      return AccountPodTargetBinding.newBuilder()
          .setInventorySnapshotDigest("f".repeat(64))
          .setEnvironmentId("prod")
          .setClusterId("cluster-a")
          .setClusterIncarnationUid("11111111-1111-4111-8111-111111111111")
          .setNamespace(NAMESPACE)
          .setNamespaceUid("22222222-2222-4222-8222-222222222222")
          .setApiBindingRevision("api-r1")
          .setApiBindingDigest("8".repeat(64))
          .setInventoryBindingRevision("inventory-r1")
          .setInventoryBindingDigest("d".repeat(64))
          .setValidatorId("game-session-service")
          .setDeploymentUid("33333333-3333-4333-8333-333333333333")
          .setPodUid("44444444-4444-4444-8444-444444444444")
          .setPodIp("10.0.0.12")
          .setImage("registry.example/game-session@sha256:" + "a".repeat(64))
          .setVerifierConfigSha256("b".repeat(64))
          .setApplicabilityMatrixDigest("c".repeat(64))
          .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
          .setDirectPodEndpoint("grpcs://10.0.0.12:6565")
          .setCanonicalServiceUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service")
          .setServerLeafSpkiSha256("e".repeat(64))
          .build();
    }

    private GetCurrentReadinessProbeOwnerResponse ownerResponse() {
      var parsed = new GameSessionJwtReadinessReceiverProtoMapper().parse(request);
      return GetCurrentReadinessProbeOwnerResponse.newBuilder()
          .setSchemaVersion(1)
          .setOwnerRecordFound(true)
          .setCurrentCoordinates(coordinates)
          .setCompactTokenSha256(parsed.compactTokenSha256())
          .setCurrentState(GetCurrentReadinessProbeOwnerResponse.ProbeState.ISSUED)
          .setCurrentPodTarget(target)
          .build();
    }
  }
}
