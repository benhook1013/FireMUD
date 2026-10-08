package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedStartLocationReadServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldPublishedStartLocationClientTest {
  @Test
  void rejectsPlaintextAndWrongNamespaceBeforeCreatingTransport(@TempDir Path directory)
      throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var client =
        new WorldPublishedStartLocationClient(
            new ServiceEndpointsProperties(), tls(directory), channelFactory, "test");
    try {
      var request = WorldPublishedStartLocationGrpcCodecTest.evidence().request();
      assertThatThrownBy(() -> client.read(withNamespace(request, "other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesExactWorldPeerCredentialsAndReadsValidatedEvidence(@TempDir Path directory)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:6565");
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new WorldPublishedStartLocationClient(endpoints, tls(directory), channelFactory, "test");
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
      AbstractStub<?> initializedStub = initializedStub(client);
      assertThat(initializedStub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initializedStub.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");

      WorldPublishedStartLocationEvidence evidence =
          WorldPublishedStartLocationGrpcCodecTest.evidence();
      var stub =
          mock(
              WorldPublishedStartLocationReadServiceGrpc
                  .WorldPublishedStartLocationReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      setStub(client, stub);
      var response = WorldPublishedStartLocationGrpcCodec.toResponse(evidence.request(), evidence);
      when(stub.readWorldPublishedStartLocation(any())).thenReturn(response);
      assertThat(client.read(evidence.request()).canonicalBytes())
          .containsExactly(evidence.canonicalBytes());

      when(stub.readWorldPublishedStartLocation(any()))
          .thenReturn(
              response.toBuilder()
                  .setAppliedResultBytes(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
                  .build());
      assertThatThrownBy(() -> client.read(evidence.request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid published selector evidence");
      verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    } finally {
      client.close();
    }
  }

  private static AbstractStub<?> initializedStub(WorldPublishedStartLocationClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(WorldPublishedStartLocationClient client, AbstractStub<?> stub)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
  }

  private static Object readField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static WorldPublishedStartLocationEvidence.Request withNamespace(
      WorldPublishedStartLocationEvidence.Request source, String namespace) {
    return new WorldPublishedStartLocationEvidence.Request(
        namespace,
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.intakeRequestId(),
        source.publicationFence(),
        source.publicationRequestId(),
        source.requestDigest(),
        source.versionStateEpoch(),
        source.publishWorkflowId(),
        source.appliedCommitId(),
        source.contentDigest(),
        source.digestSchemaVersion(),
        source.worldAffectedTuples());
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
