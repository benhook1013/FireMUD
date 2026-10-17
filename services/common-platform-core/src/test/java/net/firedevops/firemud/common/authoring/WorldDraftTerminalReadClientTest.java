package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldDraftTerminalReadClientTest {
  @Test
  void rejectsPlaintextBeforeCreatingAChannel(@TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties tls = tls(directory);
    tls.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new WorldDraftTerminalReadClient(
                    new ServiceEndpointsProperties(), tls, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsWrongNamespaceAndUninitializedUseBeforeTransport(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    WorldDraftTerminalReadClient client =
        new WorldDraftTerminalReadClient(
            new ServiceEndpointsProperties(), tls(directory), channelFactory, "test");
    try {
      assertThatThrownBy(() -> client.read(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(request("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesAnExactWorldPeerAuthenticatedStubForConfiguredEndpoint(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:6565");
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    WorldDraftTerminalReadClient client =
        new WorldDraftTerminalReadClient(endpoints, tls(directory), channelFactory, "test");
    try {
      client.init();

      verify(channelFactory)
          .buildChannel(
              eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
      AbstractStub<?> stub = initializedStub(client);
      assertThat(stub.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(DraftTerminalReadGrpcLimits.MAX_RESPONSE_WIRE_BYTES);
      assertThat(stub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(stub.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");
    } finally {
      client.close();
    }
  }

  private static AbstractStub<?> initializedStub(WorldDraftTerminalReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  @Test
  void clientDecodesCommittedCarrierAndRejectsSubstitutedResult(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            any(String.class), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new WorldDraftTerminalReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      client.init();
      var stub =
          mock(
              net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadServiceGrpc
                  .WorldDraftTerminalReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(eq(5L), eq(java.util.concurrent.TimeUnit.SECONDS)))
          .thenReturn(stub);
      Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      stubField.setAccessible(true);
      stubField.set(client, stub);
      var request = request("test");
      var committed = AuthoringFixtures.committedFreshGraphReadback(request);
      var response =
          WorldDraftTerminalReadGrpcCodec.toResponse(request, java.util.Optional.of(committed));
      when(stub.readWorldDraftTerminalOutcome(any())).thenReturn(response);
      assertThat(client.read(request).ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(committed.canonicalBytes());
      var changed =
          new DraftAuthorizationFenceBinding.OwnerReadback(
              committed.owner(),
              committed.outcome(),
              committed.operationId(),
              committed.commitId(),
              committed.fenceId(),
              committed.inputDigest(),
              committed.fullBinding(),
              new byte[] {1});
      when(stub.readWorldDraftTerminalOutcome(any()))
          .thenReturn(
              response.toBuilder()
                  .setOwnerReadbackBytes(
                      com.google.protobuf.ByteString.copyFrom(changed.canonicalBytes()))
                  .build());
      assertThatThrownBy(() -> client.read(request))
          .hasMessageContaining("invalid terminal readback evidence");
    } finally {
      client.close();
    }
  }

  private static Object readField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static WorldDraftTerminalReadEvidence.Request request(String namespace) throws Exception {
    var canonical = AuthoringFixtures.freshGraphRequest();
    return new WorldDraftTerminalReadEvidence.Request(
        canonical.schemaVersion(),
        namespace,
        canonical.readRequestId(),
        canonical.originalAccountBinding());
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
