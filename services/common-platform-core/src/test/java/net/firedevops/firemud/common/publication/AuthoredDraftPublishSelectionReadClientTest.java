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
import net.firedevops.firemud.gamedesign.v1.GameDesignSelectedDraftPublicationReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthoredDraftPublishSelectionReadClientTest {
  @Test
  void requiresReadableFileBackedMaterialAndCanonicalNamespace(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    for (String badPath :
        java.util.List.of(
            "",
            "classpath:client.crt",
            directory.resolve("absent.crt").toString(),
            directory.toString())) {
      var invalid = tls(directory);
      invalid.setCertChain(badPath);
      assertThatThrownBy(
              () ->
                  new AuthoredDraftPublishSelectionReadClient(
                      new ServiceEndpointsProperties(), invalid, factory, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var noKey = tls(directory);
    noKey.setPrivateKey(null);
    assertThatThrownBy(
            () ->
                new AuthoredDraftPublishSelectionReadClient(
                    new ServiceEndpointsProperties(), noKey, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var noCa = tls(directory);
    noCa.setCaCert(null);
    assertThatThrownBy(
            () ->
                new AuthoredDraftPublishSelectionReadClient(
                    new ServiceEndpointsProperties(), noCa, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var valid = tls(directory);
    assertThatThrownBy(
            () ->
                new AuthoredDraftPublishSelectionReadClient(
                    new ServiceEndpointsProperties(), valid, factory, "Test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void closedClientCannotInitializeOrRead(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var client =
        new AuthoredDraftPublishSelectionReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    client.close();
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> client.read(AuthoredDraftPublishSelectionReadGrpcCodecTest.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(factory);
  }

  @Test
  void rejectsPlaintextWrongNamespaceAndUninitializedCallsBeforeTransport(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AuthoredDraftPublishSelectionReadClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var client =
        new AuthoredDraftPublishSelectionReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      var request = AuthoredDraftPublishSelectionReadGrpcCodecTest.request();
      assertThatThrownBy(
              () ->
                  client.read(
                      new AuthoredDraftPublishSelectionReadEvidence.Request(
                          request.schemaVersion(),
                          "other",
                          request.readRequestId(),
                          request.originalSelection(),
                          request.selectionDigest())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      verifyNoInteractions(factory);
    } finally {
      client.close();
    }
  }

  @Test
  void configuresExactGameDesignPeerAndReturnsOnlyExactSelectedResponse(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("design.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("design.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new AuthoredDraftPublishSelectionReadClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      AbstractStub<?> initialized = initializedStub(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initialized.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");

      var stub =
          mock(
              GameDesignSelectedDraftPublicationReadServiceGrpc
                  .GameDesignSelectedDraftPublicationReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      setStub(client, stub);
      var request = AuthoredDraftPublishSelectionReadGrpcCodecTest.request();
      var held = AuthoredDraftPublishSelectionReadGrpcCodec.toSelectedResponse(request);
      when(stub.readSelectedDraftPublication(any())).thenReturn(held);

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires no owner transaction");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires no owner transaction");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      verifyNoInteractions(stub);

      assertThat(client.read(request).request()).isEqualTo(request);
      verify(stub).readSelectedDraftPublication(any());

      when(stub.readSelectedDraftPublication(any()))
          .thenReturn(held.toBuilder().setTargetNamespace("other").build());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid selected Draft publication evidence");
      when(stub.readSelectedDraftPublication(any()))
          .thenReturn(ReadSelectedDraftPublicationResponse.getDefaultInstance());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid selected Draft publication evidence");
      when(stub.readSelectedDraftPublication(any()))
          .thenThrow(Status.UNAVAILABLE.withDescription("offline").asRuntimeException());
      assertThatThrownBy(() -> client.read(request))
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

  private static AbstractStub<?> initializedStub(AuthoredDraftPublishSelectionReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(
      AuthoredDraftPublishSelectionReadClient client,
      GameDesignSelectedDraftPublicationReadServiceGrpc
              .GameDesignSelectedDraftPublicationReadServiceBlockingStub
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
