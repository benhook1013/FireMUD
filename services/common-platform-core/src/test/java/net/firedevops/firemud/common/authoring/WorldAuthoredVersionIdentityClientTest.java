package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredVersionIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldAuthoredVersionIdentityClientTest {
  @Test
  void refusesPlaintextMissingMaterialAndClasspathBeforeTransport(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var tls = tls(directory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldAuthoredVersionIdentityClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setPlaintext(false);
    tls.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldAuthoredVersionIdentityClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setCaCert(directory.resolve("missing.crt").toString());
    assertThatThrownBy(
            () ->
                new WorldAuthoredVersionIdentityClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void refusesWrongNamespaceUninitializedAndClosedUse(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var client =
        new WorldAuthoredVersionIdentityClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      assertThatThrownBy(
              () -> client.associate(WorldAuthoredVersionIdentityGrpcCodecTest.request("other")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> client.associate(WorldAuthoredVersionIdentityGrpcCodecTest.request("test")))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(factory);
    } finally {
      client.close();
    }
    assertThatThrownBy(client::init).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> client.associate(WorldAuthoredVersionIdentityGrpcCodecTest.request("test")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void configuresWorldEndpointAndExactPeerAndDecodesOnlyClosedResults(@TempDir Path directory)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(
            eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(mock(ManagedChannel.class));
    var client = new WorldAuthoredVersionIdentityClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      var field = stubField();
      var actual =
          (WorldAuthoredVersionIdentityServiceGrpc.WorldAuthoredVersionIdentityServiceBlockingStub)
              field.get(client);
      assertThat(actual.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(WorldAuthoredVersionIdentityGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
      assertThat(client.workloadNamespace()).isEqualTo("test");
      assertThat(actual.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      var peerField =
          GrpcServerPeerIdentityCallCredentials.class.getDeclaredField("expectedPeerUri");
      peerField.setAccessible(true);
      assertThat(peerField.get(actual.getCallOptions().getCredentials()))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");
      verify(factory)
          .buildChannel(
              eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
      var stub =
          mock(
              WorldAuthoredVersionIdentityServiceGrpc
                  .WorldAuthoredVersionIdentityServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      field.set(client, stub);
      var request = WorldAuthoredVersionIdentityGrpcCodecTest.request("test");
      var response =
          WorldAuthoredVersionIdentityGrpcCodec.toResponse(
              WorldAuthoredVersionIdentityGrpcCodecTest.committed(request));
      when(stub.associateAuthoredWorldVersion(any())).thenReturn(response);
      assertThat(client.associate(request).request()).isEqualTo(request);
      verify(stub)
          .associateAuthoredWorldVersion(WorldAuthoredVersionIdentityGrpcCodec.toRequest(request));
      when(stub.associateAuthoredWorldVersion(any()))
          .thenReturn(response.toBuilder().clearVersionStateEvidence().build());
      assertThatThrownBy(() -> client.associate(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid authored Version association evidence");
      var unavailable = Status.UNAVAILABLE.withDescription("lost response").asRuntimeException();
      when(stub.associateAuthoredWorldVersion(any())).thenThrow(unavailable);
      assertThatThrownBy(() -> client.associate(request)).isSameAs(unavailable);
      var lostAck =
          Status.DEADLINE_EXCEEDED.withDescription("owner may have committed").asRuntimeException();
      doThrow(lostAck).when(stub).associateAuthoredWorldVersion(any());
      assertThatThrownBy(() -> client.associate(request)).isSameAs(lostAck);
    } finally {
      client.close();
    }
  }

  private static Field stubField() throws ReflectiveOperationException {
    var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return field;
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "fixture").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "fixture").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "fixture").toString());
    return tls;
  }
}
