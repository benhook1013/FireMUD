package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class GameSessionCanonicalGameplayRosterOwnerReadClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID REQUEST_UUID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_UUID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_UUID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REALM_UUID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID NAMESPACE_UUID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID INSTANCE_UUID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID VERSION_UUID = uuid("77777777-7777-4777-8777-777777777777");

  @Test
  void forwardsExactRequestAndReturnsOnlyCodecValidatedEvidence() throws Exception {
    var request = request(NAMESPACE);
    var response =
        GetCanonicalGameplayRosterOwnerReadResponse.newBuilder()
            .setRequest(CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(request))
            .setGameSessionOwnerProof(ByteString.copyFrom(new byte[] {1}))
            .setPublishedPolicySetEvidence(ByteString.copyFrom(new byte[] {2}))
            .setAdmissionPointerSnapshotDigest("a".repeat(64))
            .build();
    var evidence = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
    var stub = mockStub();
    when(stub.getCanonicalGameplayRosterOwnerRead(any())).thenReturn(response);
    var client = newClient(stub);

    try (MockedStatic<CanonicalGameplayRosterOwnerReadGrpcCodec> codec =
        Mockito.mockStatic(CanonicalGameplayRosterOwnerReadGrpcCodec.class)) {
      codec
          .when(() -> CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(request))
          .thenCallRealMethod();
      codec
          .when(() -> CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(request, response))
          .thenReturn(evidence);

      assertThat(client.getCanonicalGameplayRosterOwnerRead(request)).isSameAs(evidence);

      ArgumentCaptor<GetCanonicalGameplayRosterOwnerReadRequest> captor =
          ArgumentCaptor.forClass(GetCanonicalGameplayRosterOwnerReadRequest.class);
      verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
      verify(stub).getCanonicalGameplayRosterOwnerRead(captor.capture());
      var wire = captor.getValue();
      assertThat(wire.getSchemaVersion()).isEqualTo(1);
      assertThat(wire.getRequestUuid()).isEqualTo(REQUEST_UUID.toString());
      assertThat(wire.getCanonicalAccountUuid()).isEqualTo(ACCOUNT_UUID.toString());
      assertThat(wire.getTargetNamespace()).isEqualTo(NAMESPACE);
      assertThat(wire.getCanonicalTenantUuid()).isEqualTo(TENANT_UUID.toString());
      assertThat(wire.getWorldSlug()).isEqualTo("earth");
      assertThat(wire.getRealmUuid()).isEqualTo(REALM_UUID.toString());
      assertThat(wire.getRealmSlug()).isEqualTo("main");
      assertThat(wire.getPlayableStateNamespaceUuid()).isEqualTo(NAMESPACE_UUID.toString());
      assertThat(wire.getPlayableStateScope()).isEqualTo("SHARED");
      assertThat(wire.getCanonicalGameInstanceUuid()).isEqualTo(INSTANCE_UUID.toString());
      assertThat(wire.getCanonicalVersionUuid()).isEqualTo(VERSION_UUID.toString());
      assertThat(wire.getExpectedCatalogRevision()).isEqualTo(71L);
      assertThat(wire.getExpectedPointerVersion()).isEqualTo(73L);
      assertThat(wire.getExpectedActiveWorldEpoch()).isEqualTo(82L);
      assertThat(wire.getUnknownFields().asMap()).isEmpty();
      assertThat(response.getAdmissionPointerSnapshotDigest()).isEqualTo("a".repeat(64));
      codec.verify(() -> CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(request, response));
    }
  }

  @Test
  void rejectsWrongNamespaceAndUninitializedClientWithoutFallbackOrChannelUse() throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var uninitialized = newClientWithoutStub(channelFactory);
    assertThatThrownBy(() -> uninitialized.getCanonicalGameplayRosterOwnerRead(request("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target namespace");
    assertThatThrownBy(() -> uninitialized.getCanonicalGameplayRosterOwnerRead(request(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsAbsentResponseAndCodecRejectedTupleOrOwnerEvidence() throws Exception {
    var request = request(NAMESPACE);
    var absentStub = mockStub();
    when(absentStub.getCanonicalGameplayRosterOwnerRead(any())).thenReturn(null);
    assertThatThrownBy(() -> newClient(absentStub).getCanonicalGameplayRosterOwnerRead(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("response is absent");

    var response =
        GetCanonicalGameplayRosterOwnerReadResponse.newBuilder()
            .setRequest(CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(request))
            .setGameSessionOwnerProof(ByteString.copyFrom(new byte[] {1}))
            .setPublishedPolicySetEvidence(ByteString.copyFrom(new byte[] {2}))
            .setAdmissionPointerSnapshotDigest("a".repeat(64))
            .build();
    var stub = mockStub();
    when(stub.getCanonicalGameplayRosterOwnerRead(any())).thenReturn(response);
    try (MockedStatic<CanonicalGameplayRosterOwnerReadGrpcCodec> codec =
        Mockito.mockStatic(CanonicalGameplayRosterOwnerReadGrpcCodec.class)) {
      codec
          .when(() -> CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(request))
          .thenCallRealMethod();
      codec
          .when(() -> CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(request, response))
          .thenThrow(new IllegalArgumentException("changed tuple, counters, policy, or release"));

      assertThatThrownBy(() -> newClient(stub).getCanonicalGameplayRosterOwnerRead(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("response is invalid")
          .hasCauseInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void requiresFileBackedMtlsAndPinsExactSameNamespaceGameSessionPeer(@TempDir Path directory)
      throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var invalidTls = mtlsProperties();
    invalidTls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameSessionCanonicalGameplayRosterOwnerReadClient(
                    new ServiceEndpointsProperties(), invalidTls, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var tls = mtlsProperties(directory);
    var channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-session-service:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    var client =
        new GameSessionCanonicalGameplayRosterOwnerReadClient(
            new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE);
    assertThat(installedServerPeerUri(client))
        .isEqualTo("spiffe://firemud/ns/test/sa/game-session-service");
    verifyNoInteractions(channelFactory);
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-session-service:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static GameSessionCanonicalGameplayRosterOwnerReadClient newClient(
      CanonicalGameplayRosterOwnerReadServiceGrpc
              .CanonicalGameplayRosterOwnerReadServiceBlockingStub
          stub)
      throws Exception {
    var client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static GameSessionCanonicalGameplayRosterOwnerReadClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new GameSessionCanonicalGameplayRosterOwnerReadClient(
        new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);
  }

  private static CanonicalGameplayRosterOwnerReadServiceGrpc
          .CanonicalGameplayRosterOwnerReadServiceBlockingStub
      mockStub() {
    var stub =
        mock(
            CanonicalGameplayRosterOwnerReadServiceGrpc
                .CanonicalGameplayRosterOwnerReadServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request request(String targetNamespace) {
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        REQUEST_UUID,
        ACCOUNT_UUID,
        targetNamespace,
        TENANT_UUID,
        "earth",
        REALM_UUID,
        "main",
        NAMESPACE_UUID,
        "SHARED",
        INSTANCE_UUID,
        VERSION_UUID,
        71L,
        73L,
        82L);
  }

  private static String installedServerPeerUri(
      GameSessionCanonicalGameplayRosterOwnerReadClient client) throws Exception {
    Field field = client.getClass().getDeclaredField("serverPeerIdentityInterceptor");
    field.setAccessible(true);
    var interceptor = (GrpcServerPeerIdentityClientInterceptor) field.get(client);
    Field peerField =
        GrpcServerPeerIdentityClientInterceptor.class.getDeclaredField("expectedPeerUri");
    peerField.setAccessible(true);
    return (String) peerField.get(interceptor);
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain("entity-management-client.crt");
    tls.setPrivateKey("entity-management-client.key");
    tls.setCaCert("game-session-ca.crt");
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.createFile(directory.resolve("entity.crt")).toString());
    tls.setPrivateKey(Files.createFile(directory.resolve("entity.key")).toString());
    tls.setCaCert(Files.createFile(directory.resolve("game-session-ca.crt")).toString());
    return tls;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
