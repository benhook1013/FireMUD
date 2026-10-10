package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
import net.firedevops.firemud.common.gamedesign.StartSessionPublishedRealmPolicyReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.gamedesign.v1.GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transport configuration and fail-closed client tests; no mocked reply is owner producer proof.
 */
class GameDesignPublishedRealmPolicyClientTest {
  @AfterEach
  void clearAmbientTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresReadableFileBackedMtlsAndCanonicalNamespace(@TempDir Path directory)
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
                  new GameDesignPublishedRealmPolicyClient(
                      new ServiceEndpointsProperties(), invalid, factory, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    var noKey = tls(directory);
    noKey.setPrivateKey(null);
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), noKey, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var noCa = tls(directory);
    noCa.setCaCert(null);
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), noCa, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var valid = tls(directory);
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), valid, factory, "Test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void closedClientCannotInitializeOrRead(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var client = newClient(directory, factory);
    client.close();

    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");

    var request = mock(StartSessionPublishedRealmPolicyReadGrpcCodec.ReadRequest.class);
    when(request.targetNamespace()).thenReturn("test");
    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionPublishedRealmPolicyReadGrpcCodec.toRequest(request))
          .thenReturn(ReadStartSessionPublishedRealmPolicyRequest.getDefaultInstance());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
    }
    verifyNoInteractions(factory);
  }

  @Test
  void rejectsAmbientTransactionOrSynchronizationBeforeCodecAndTransport(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var client = newClient(directory, factory);
    var stub = mockStub();
    setStub(client, stub);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient transaction synchronization");
      codec.verifyNoInteractions();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient transaction synchronization");
      codec.verifyNoInteractions();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(stub);
    client.close();
  }

  @Test
  void pinsExactGameDesignPeerAndAppliesLocalBoundsAndDeadline(@TempDir Path directory)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("design.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("design.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new GameDesignPublishedRealmPolicyClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      AbstractStub<?> initialized = initializedStub(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initialized.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");

      var stub = mockStub();
      setStub(client, stub);
      var request = mock(StartSessionPublishedRealmPolicyReadGrpcCodec.ReadRequest.class);
      when(request.targetNamespace()).thenReturn("test");
      when(stub.readStartSessionPublishedRealmPolicy(any()))
          .thenReturn(ReadStartSessionPublishedRealmPolicyResponse.getDefaultInstance());

      try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
          Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
        codec
            .when(() -> StartSessionPublishedRealmPolicyReadGrpcCodec.toRequest(request))
            .thenReturn(ReadStartSessionPublishedRealmPolicyRequest.getDefaultInstance());
        codec
            .when(
                () ->
                    StartSessionPublishedRealmPolicyReadGrpcCodec.fromResponse(
                        isNull(), any(ReadStartSessionPublishedRealmPolicyResponse.class)))
            .thenThrow(new IllegalArgumentException("transport-only test response"));

        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid or mismatched published-policy evidence");

        verify(stub)
            .withMaxInboundMessageSize(
                StartSessionPublishedRealmPolicyReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
        verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
        verify(stub).readStartSessionPublishedRealmPolicy(any());
        codec.verify(() -> StartSessionPublishedRealmPolicyReadGrpcCodec.toRequest(request));
        codec.verify(
            () ->
                StartSessionPublishedRealmPolicyReadGrpcCodec.requireRequestSerializedSize(
                    any(ReadStartSessionPublishedRealmPolicyRequest.class)));
        codec.verify(
            () ->
                StartSessionPublishedRealmPolicyReadGrpcCodec.requireResponseSerializedSize(
                    any(ReadStartSessionPublishedRealmPolicyResponse.class)));
        codec.verify(
            () ->
                StartSessionPublishedRealmPolicyReadGrpcCodec.fromResponse(
                    isNull(), any(ReadStartSessionPublishedRealmPolicyResponse.class)));
      }
    } finally {
      client.close();
    }
    verify(channel).shutdown();
  }

  private static GameDesignPublishedRealmPolicyClient newClient(
      Path directory, GrpcChannelFactory factory) throws Exception {
    return new GameDesignPublishedRealmPolicyClient(
        new ServiceEndpointsProperties(), tls(directory), factory, "test");
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    properties.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "private key").toString());
    properties.setCaCert(
        Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return properties;
  }

  private static GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
          .GameDesignStartSessionPublishedRealmPolicyReadServiceBlockingStub
      mockStub() {
    var stub =
        mock(
            GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
                .GameDesignStartSessionPublishedRealmPolicyReadServiceBlockingStub.class);
    when(stub.withMaxInboundMessageSize(
            StartSessionPublishedRealmPolicyReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES))
        .thenReturn(stub);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static AbstractStub<?> initializedStub(GameDesignPublishedRealmPolicyClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(
      GameDesignPublishedRealmPolicyClient client,
      GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
              .GameDesignStartSessionPublishedRealmPolicyReadServiceBlockingStub
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
