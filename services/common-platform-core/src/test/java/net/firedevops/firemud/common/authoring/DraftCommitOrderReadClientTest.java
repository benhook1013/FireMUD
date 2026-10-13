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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.account.v1.AccountDraftCommitOrderReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftCommitOrderReadClientTest {
  @Test
  void rejectsPlaintextWrongNamespaceAndUninitializedCallsBeforeTransport(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new DraftCommitOrderReadClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var client =
        new DraftCommitOrderReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      assertThatThrownBy(() -> client.read(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(request("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      verifyNoInteractions(factory);
    } finally {
      client.close();
    }
  }

  @Test
  void configuresExactAccountServerIdentityAndReturnsOnlyExactHeldResponse(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client = new DraftCommitOrderReadClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      AbstractStub<?> initialized = initializedStub(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initialized.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/account-service");

      var stub =
          mock(
              AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceBlockingStub
                  .class);
      when(stub.withDeadlineAfter(eq(5L), eq(java.util.concurrent.TimeUnit.SECONDS)))
          .thenReturn(stub);
      setStub(client, stub);
      var request = request("test");
      var held = DraftCommitOrderReadGrpcCodec.toHeldResponse(request);
      when(stub.readHeldOriginalCommitOrder(any())).thenReturn(held);

      assertThat(client.read(request).request()).isEqualTo(request);
      verify(stub).readHeldOriginalCommitOrder(any());

      when(stub.readHeldOriginalCommitOrder(any()))
          .thenReturn(held.toBuilder().setTargetNamespace("other").build());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid COMMIT_ORDER evidence");
      when(stub.readHeldOriginalCommitOrder(any()))
          .thenReturn(ReadHeldOriginalCommitOrderResponse.getDefaultInstance());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid COMMIT_ORDER evidence");
      when(stub.readHeldOriginalCommitOrder(any()))
          .thenThrow(Status.UNAVAILABLE.withDescription("offline").asRuntimeException());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.UNAVAILABLE);
    } finally {
      client.close();
    }
  }

  private static DraftCommitOrderReadEvidence.Request request(String namespace) {
    return new DraftCommitOrderReadEvidence.Request(
        1,
        namespace,
        java.util.UUID.fromString("33333333-3333-4333-8333-333333333333"),
        DraftCommitOrderReadGrpcCodecTest.accountBinding());
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static AbstractStub<?> initializedStub(DraftCommitOrderReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(
      DraftCommitOrderReadClient client,
      AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceBlockingStub stub)
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
