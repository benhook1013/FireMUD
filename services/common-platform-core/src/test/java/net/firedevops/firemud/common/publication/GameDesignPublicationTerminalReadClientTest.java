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
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignPublicationTerminalReadClientTest {
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
                  new GameDesignPublicationTerminalReadClient(
                      new ServiceEndpointsProperties(), invalid, factory, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var noKey = tls(directory);
    noKey.setPrivateKey(null);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadClient(
                    new ServiceEndpointsProperties(), noKey, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var noCa = tls(directory);
    noCa.setCaCert(null);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadClient(
                    new ServiceEndpointsProperties(), noCa, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var valid = tls(directory);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadClient(
                    new ServiceEndpointsProperties(), valid, factory, "Test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void closedClientCannotInitializeOrRead(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var client =
        new GameDesignPublicationTerminalReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    client.close();
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> client.read(GameDesignPublicationTerminalReadGrpcCodecTest.request()))
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
                new GameDesignPublicationTerminalReadClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var client =
        new GameDesignPublicationTerminalReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      var request = GameDesignPublicationTerminalReadGrpcCodecTest.request();
      assertThatThrownBy(
              () ->
                  client.read(
                      new GameDesignPublicationTerminalReadEvidence.Request(
                          request.schemaVersion(),
                          "other",
                          request.readRequestId(),
                          request.originalOperation())))
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
  void configuresExactGameDesignPeerAndReturnsOnlyCompleteOriginalTerminal(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("design.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("design.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new GameDesignPublicationTerminalReadClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      AbstractStub<?> initialized = initializedStub(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initialized.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");

      var stub =
          mock(
              GameDesignPublicationTerminalReadServiceGrpc
                  .GameDesignPublicationTerminalReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      setStub(client, stub);
      var request = GameDesignPublicationTerminalReadGrpcCodecTest.request();
      var held =
          GameDesignPublicationTerminalReadGrpcCodec.toResponse(
              request,
              GameDesignPublicationTerminalReadGrpcCodecTest.terminal(
                  request.operation(),
                  net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence
                      .Outcome.NO_PUBLICATION));
      when(stub.readPublicationTerminal(any())).thenReturn(held);

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
      verify(stub).readPublicationTerminal(any());

      when(stub.readPublicationTerminal(any()))
          .thenReturn(held.toBuilder().setTargetNamespace("other").build());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid publication terminal evidence");
      when(stub.readPublicationTerminal(any()))
          .thenReturn(ReadPublicationTerminalResponse.getDefaultInstance());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid publication terminal evidence");
      when(stub.readPublicationTerminal(any()))
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

  private static AbstractStub<?> initializedStub(GameDesignPublicationTerminalReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(
      GameDesignPublicationTerminalReadClient client,
      GameDesignPublicationTerminalReadServiceGrpc
              .GameDesignPublicationTerminalReadServiceBlockingStub
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
