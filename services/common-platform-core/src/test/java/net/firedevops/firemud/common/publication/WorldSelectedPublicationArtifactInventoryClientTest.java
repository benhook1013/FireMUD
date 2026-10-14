package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedDraftPublicationFreezeServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldSelectedPublicationArtifactInventoryClientTest {
  @Test
  void requiresFileBackedMtlsAndCanonicalNamespace(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldSelectedPublicationArtifactInventoryClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    assertThatThrownBy(
            () ->
                new WorldSelectedPublicationArtifactInventoryClient(
                    new ServiceEndpointsProperties(), tls(directory), factory, "Test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void usesExactWorldIdentityAndBoundedDeadlineAndRejectsInvalidReadback(@TempDir Path directory)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new WorldSelectedPublicationArtifactInventoryClient(
            endpoints, tls(directory), factory, "test");
    try {
      client.init();
      AbstractStub<?> initialized = initializedStub(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initialized.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");

      var stub =
          mock(
              WorldSelectedDraftPublicationFreezeServiceGrpc
                  .WorldSelectedDraftPublicationFreezeServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      setStub(client, stub);
      var fixture = WorldSelectedPublicationArtifactInventoryGrpcCodecTest.fixture();
      var expectedEvidence =
          WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
              fixture.freezeEvidence(), fixture.publicEvidence());
      var expectedResponse =
          WorldSelectedPublicationArtifactInventoryGrpcCodec.toResponse(expectedEvidence);
      when(stub.readSelectedPublicationArtifactInventory(any())).thenReturn(expectedResponse);

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(fixture.freezeEvidence()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ambient owner transaction");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(fixture.freezeEvidence()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ambient owner transaction");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      verifyNoInteractions(stub);

      var actual = client.read(fixture.freezeEvidence());
      assertThat(actual.canonicalBytes()).containsExactly(expectedEvidence.canonicalBytes());
      assertThat(actual.digest()).isEqualTo(expectedEvidence.digest());
      verify(stub)
          .readSelectedPublicationArtifactInventory(
              eq(
                  WorldSelectedPublicationArtifactInventoryGrpcCodec.toRequest(
                      fixture.freezeEvidence())));

      when(stub.readSelectedPublicationArtifactInventory(any()))
          .thenReturn(
              expectedResponse.toBuilder()
                  .setPublicInventoryDigest("sha256:" + "0".repeat(64))
                  .build());
      assertThatThrownBy(() -> client.read(fixture.freezeEvidence()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid selected-publication inventory evidence");
      when(stub.readSelectedPublicationArtifactInventory(any()))
          .thenThrow(Status.UNAVAILABLE.withDescription("offline").asRuntimeException());
      assertThatThrownBy(() -> client.read(fixture.freezeEvidence()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.UNAVAILABLE);
    } finally {
      client.close();
    }
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static AbstractStub<?> initializedStub(
      WorldSelectedPublicationArtifactInventoryClient client) throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(
      WorldSelectedPublicationArtifactInventoryClient client,
      WorldSelectedDraftPublicationFreezeServiceGrpc
              .WorldSelectedDraftPublicationFreezeServiceBlockingStub
          stub)
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
}
