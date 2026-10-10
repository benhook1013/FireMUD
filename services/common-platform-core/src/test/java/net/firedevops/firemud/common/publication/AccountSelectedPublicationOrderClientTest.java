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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationOrderServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stub configuration/codec units; mocked channels do not prove an mTLS handshake. */
class AccountSelectedPublicationOrderClientTest {
  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresReadableFileBackedMutualTlsAndNamespace(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var tls = tls(directory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountSelectedPublicationOrderClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setPlaintext(false);
    for (String bad :
        new String[] {
          null,
          "",
          "classpath:credential.pem",
          directory.toString(),
          directory.resolve("missing.pem").toString()
        }) {
      tls.setCertChain(bad);
      assertThatThrownBy(
              () ->
                  new AccountSelectedPublicationOrderClient(
                      new ServiceEndpointsProperties(), tls, factory, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var valid = tls(directory);
    assertThatThrownBy(
            () ->
                new AccountSelectedPublicationOrderClient(
                    new ServiceEndpointsProperties(), valid, factory, "Test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void configuresExactAccountPeerDeadlineAndValidatesEchoOutsideSql(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    when(factory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new AccountSelectedPublicationOrderClient(endpoints, tls(directory), factory, "test");
    var binding = AccountPublicationAuthorizationReadGrpcCodecTest.binding();
    var request =
        AccountSelectedPublicationOrderGrpcCodec.Request.create(
            "test", binding.input().selection(), "original.secret.credential");
    try {
      assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(IllegalStateException.class);
      client.init();
      var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      var initialized = (io.grpc.stub.AbstractStub<?>) field.get(client);
      var credentials = initialized.getCallOptions().getCredentials();
      assertThat(credentials).isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      var peerField =
          GrpcServerPeerIdentityCallCredentials.class.getDeclaredField("expectedPeerUri");
      peerField.setAccessible(true);
      assertThat(peerField.get(credentials))
          .isEqualTo("spiffe://firemud/ns/test/sa/account-service");
      var stub =
          mock(
              AccountSelectedPublicationOrderServiceGrpc
                  .AccountSelectedPublicationOrderServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
      when(stub.withCallCredentials(any())).thenReturn(stub);
      field.set(client, stub);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.clearSynchronization();
      var wrongNamespace =
          AccountSelectedPublicationOrderGrpcCodec.Request.create(
              "other", binding.input().selection(), "original.secret.credential");
      assertThatThrownBy(() -> client.authorize(wrongNamespace))
          .isInstanceOf(IllegalArgumentException.class);
      verifyNoInteractions(stub);
      var response = AccountSelectedPublicationOrderGrpcCodec.toResponse(request, binding);
      when(stub.authorizeSelectedPublication(any())).thenReturn(response);
      assertThat(client.authorize(request).canonicalBytes()).isEqualTo(binding.canonicalBytes());
      verify(stub).withDeadlineAfter(5, TimeUnit.SECONDS);
      var wire =
          org.mockito.ArgumentCaptor.forClass(
              net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest.class);
      verify(stub).authorizeSelectedPublication(wire.capture());
      assertThat(wire.getValue().toString()).doesNotContain(request.originalCreatorCredential());
      var perCall = org.mockito.ArgumentCaptor.forClass(io.grpc.CallCredentials.class);
      verify(stub).withCallCredentials(perCall.capture());
      assertThat(perCall.getValue()).isInstanceOf(io.grpc.CompositeCallCredentials.class);
      when(stub.authorizeSelectedPublication(any()))
          .thenReturn(response.toBuilder().setTargetNamespace("other").build());
      assertThatThrownBy(() -> client.authorize(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(request.originalCreatorCredential());
      when(stub.authorizeSelectedPublication(any()))
          .thenThrow(
              io.grpc.Status.UNAVAILABLE
                  .withDescription(request.originalCreatorCredential())
                  .asRuntimeException());
      assertThatThrownBy(() -> client.authorize(request))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .hasMessageNotContaining(request.originalCreatorCredential())
          .hasNoCause();
    } finally {
      client.close();
    }
    assertThatThrownBy(client::init).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(IllegalStateException.class);
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private-key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "ca").toString());
    return tls;
  }
}
