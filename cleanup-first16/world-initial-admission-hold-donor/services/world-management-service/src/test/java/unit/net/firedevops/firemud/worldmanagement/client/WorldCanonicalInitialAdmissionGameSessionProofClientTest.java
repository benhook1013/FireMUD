package net.firedevops.firemud.worldmanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider.GameSessionOwnerProofClientPins;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WorldCanonicalInitialAdmissionGameSessionProofClientTest {
  @Test
  void methodSpecificProofMustEchoTheWholeFixtureHoldIdentity() {
    HoldIdentity identity = identity();
    var proof =
        WorldCanonicalInitialAdmissionGameSessionProofClient.decodeExact(
            identity, response(identity));

    assertThat(proof.holdIdentity()).isEqualTo(identity);
    assertThat(proof.outcome())
        .isEqualTo(net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
  }

  @Test
  void aProofForAnotherCanonicalInstanceIsRejected() {
    HoldIdentity identity = identity();
    var borrowed =
        response(identity).toBuilder().setCanonicalGameInstanceId(UUID.randomUUID().toString()).build();

    assertThatThrownBy(
            () -> WorldCanonicalInitialAdmissionGameSessionProofClient.decodeExact(identity, borrowed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed the complete hold identity");
  }

  @Test
  void ownerReadUsesTheDedicatedServiceAndExactReceiptPinnedPeer(@TempDir Path directory)
      throws Exception {
    var stub = chainableStub();
    when(stub.getCanonicalInitialAdmissionOwnerProof(any())).thenReturn(response(identity()));
    var client = newClient(directory, stub);
    var pins = mock(GameSessionOwnerProofClientPins.class);
    when(pins.gameSessionUriSan()).thenReturn("spiffe://firemud/ns/dev/sa/game-session-service");
    when(pins.gameSessionLeafSha256()).thenReturn("c".repeat(64));

    var result = client.readOwnerProof(identity(), pins);

    assertThat(result.holdIdentity()).isEqualTo(identity());
    assertThat(CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .getGetCanonicalInitialAdmissionOwnerProofMethod()
            .getFullMethodName())
        .isEqualTo(
            "game_session.v1.CanonicalInitialAdmissionOwnerProofReadService/GetCanonicalInitialAdmissionOwnerProof");
    var credentials = ArgumentCaptor.forClass(CallCredentials.class);
    verify(stub).withCallCredentials(credentials.capture());
    assertThat(credentials.getValue()).isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
    assertThat(readPin(credentials.getValue(), "expectedPeerUri"))
        .isEqualTo("spiffe://firemud/ns/dev/sa/game-session-service");
    assertThat(readPin(credentials.getValue(), "expectedLeafSha256")).isEqualTo("c".repeat(64));
    var interceptors = ArgumentCaptor.forClass(ClientInterceptor[].class);
    verify(stub).withInterceptors(interceptors.capture());
    assertThat(interceptors.getValue()).hasSize(1);
    assertThat(interceptors.getValue()[0])
        .isInstanceOf(GrpcServerPeerIdentityClientInterceptor.class);
    assertThat(readPin(interceptors.getValue()[0], "expectedPeerUri"))
        .isEqualTo("spiffe://firemud/ns/dev/sa/game-session-service");
    assertThat(readPin(interceptors.getValue()[0], "expectedLeafSha256")).isEqualTo("c".repeat(64));
  }

  @Test
  void clientRequiresFileBackedMutualTlsBeforeItCanBeConstructed(@TempDir Path directory)
      throws Exception {
    var missingTls = new CommonGrpcClientProperties();
    assertThatThrownBy(
            () ->
                new WorldCanonicalInitialAdmissionGameSessionProofClient(
                    new ServiceEndpointsProperties(), missingTls, new GrpcChannelFactory()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var plaintext = fileBackedTls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldCanonicalInitialAdmissionGameSessionProofClient(
                    new ServiceEndpointsProperties(), plaintext, new GrpcChannelFactory()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
  }

  private static CanonicalInitialAdmissionOwnerProofReadServiceGrpc
          .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub
      chainableStub() {
    var stub =
        mock(
            CanonicalInitialAdmissionOwnerProofReadServiceGrpc
                .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub.class);
    doReturn(stub).when(stub).withDeadlineAfter(anyLong(), any(TimeUnit.class));
    doReturn(stub).when(stub).withCallCredentials(any(CallCredentials.class));
    doReturn(stub).when(stub).withInterceptors(any(ClientInterceptor[].class));
    return stub;
  }

  private static WorldCanonicalInitialAdmissionGameSessionProofClient newClient(
      Path directory,
      CanonicalInitialAdmissionOwnerProofReadServiceGrpc
              .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub
          stub)
      throws Exception {
    var tls = fileBackedTls(directory);
    var client =
        new WorldCanonicalInitialAdmissionGameSessionProofClient(
            new ServiceEndpointsProperties(), tls, new GrpcChannelFactory());
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    Field initializedField =
        WorldCanonicalInitialAdmissionGameSessionProofClient.class.getDeclaredField("initialized");
    initializedField.setAccessible(true);
    initializedField.setBoolean(client, true);
    return client;
  }

  private static CommonGrpcClientProperties fileBackedTls(Path directory) throws Exception {
    Path cert = Files.createFile(directory.resolve("world.crt"));
    Path key = Files.createFile(directory.resolve("world.key"));
    Path ca = Files.createFile(directory.resolve("ca.crt"));
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(cert.toString());
    tls.setPrivateKey(key.toString());
    tls.setCaCert(ca.toString());
    return tls;
  }

  private static String readPin(Object evidence, String fieldName) throws Exception {
    Field field = evidence.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return (String) field.get(evidence);
  }

  private static GetCanonicalInitialAdmissionOwnerProofResponse response(HoldIdentity identity) {
    var request = identity.request();
    return GetCanonicalInitialAdmissionOwnerProofResponse.newBuilder()
        .setTargetNamespace(request.targetNamespace())
        .setInitialAdmissionRequestId(request.initialAdmissionRequestId())
        .setInitialAdmissionRequestDigest(request.initialAdmissionRequestDigest())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setRealmId(request.realmId().toString())
        .setPlayableStateNamespaceId(request.playableStateNamespaceId().toString())
        .setPlayableStateScope(request.playableStateScope())
        .setCanonicalGameInstanceId(request.canonicalGameInstanceId().toString())
        .setCanonicalVersionId(request.canonicalVersionId().toString())
        .setActiveLifecycleEpoch(request.activeLifecycleEpoch())
        .setExpectedCatalogRevision(request.expectedCatalogRevision())
        .setOrigin(
            CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER)
        .setHoldId(identity.holdId().toString())
        .setHoldFence(identity.holdFence().toString())
        .setHoldBindingDigest(identity.holdBindingDigest())
        .setOutcome(
            CanonicalInitialAdmissionOwnerProofOutcome
                .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING)
        .setAuthorityKind(AuthorityKind.RUN_OWNED_FIXTURE.name())
        .setFixturePolicySealEvidenceDigest(request.fixturePolicySealEvidenceDigest())
        .build();
  }

  private static HoldIdentity identity() {
    var request =
        new Request(
            "dev",
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            "fixture-world",
            UUID.fromString("20000000-0000-0000-0000-000000000002"),
            UUID.fromString("30000000-0000-0000-0000-000000000003"),
            "SHARED",
            UUID.fromString("40000000-0000-0000-0000-000000000004"),
            UUID.fromString("50000000-0000-0000-0000-000000000005"),
            2L,
            "first-open-fixture-request",
            "a".repeat(64),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null,
            AuthorityKind.RUN_OWNED_FIXTURE,
            "sha256:" + "b".repeat(64));
    return new HoldIdentity(
        request,
        UUID.fromString("60000000-0000-0000-0000-000000000006"),
        UUID.fromString("70000000-0000-0000-0000-000000000007"));
  }
}
