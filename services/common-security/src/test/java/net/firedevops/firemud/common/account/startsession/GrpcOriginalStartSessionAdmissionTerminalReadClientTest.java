package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.GrpcOriginalStartSessionAdmissionTerminalReadClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalGrpcCodec;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionAdmissionTerminalReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic mTLS client setup tests; no Game Session producer or database is exercised. */
class GrpcOriginalStartSessionAdmissionTerminalReadClientTest {
  private static final String NAMESPACE = "world-runtime";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresFileBackedMtlsAndCanonicalNamespace(@TempDir Path directory) throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var tls = tls(directory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcOriginalStartSessionAdmissionTerminalReadClient(
                    new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    tls.setPlaintext(false);
    String[] invalidPaths = {
      null,
      "",
      "classpath:material.pem",
      directory.toString(),
      directory.resolve("missing.pem").toString()
    };
    String[] expectedMessages = {
      "file-backed",
      "file-backed",
      "file-backed",
      "existing readable file",
      "existing readable file"
    };
    for (int index = 0; index < invalidPaths.length; index++) {
      tls.setCertChain(invalidPaths[index]);
      assertThatThrownBy(
              () ->
                  new GrpcOriginalStartSessionAdmissionTerminalReadClient(
                      new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(expectedMessages[index]);
    }
    assertThatThrownBy(
            () ->
                new GrpcOriginalStartSessionAdmissionTerminalReadClient(
                    new ServiceEndpointsProperties(), tls(directory), channelFactory, "Test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void explicitlyInitializesPinsPeerAndCallsBoundedTerminalRead(@TempDir Path directory)
      throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("game-session.internal:6565");
    when(channelFactory.buildChannel(
            eq("game-session.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    var client =
        new GrpcOriginalStartSessionAdmissionTerminalReadClient(
            endpoints, tls(directory), channelFactory, NAMESPACE);
    var request = mock(OriginalStartSessionAdmissionTerminalRequest.class);
    when(request.targetNamespace()).thenReturn(NAMESPACE);
    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");

    try {
      client.init();
      var stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      stubField.setAccessible(true);
      var initialized = (io.grpc.stub.AbstractStub<?>) stubField.get(client);
      assertThat(initialized.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(OriginalStartSessionAdmissionTerminalGrpcCodec.MAX_RESPONSE_BYTES);
      assertThat(initialized.getCallOptions().getCompressor()).isEqualTo("gzip");
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      var credentials = initialized.getCallOptions().getCredentials();
      var expectedPeerField =
          GrpcServerPeerIdentityCallCredentials.class.getDeclaredField("expectedPeerUri");
      expectedPeerField.setAccessible(true);
      assertThat(expectedPeerField.get(credentials))
          .isEqualTo("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service");

      var stub =
          mock(
              OriginalStartSessionAdmissionTerminalReadServiceGrpc
                  .OriginalStartSessionAdmissionTerminalReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
      when(request.canonicalBytes()).thenReturn(new byte[] {1});
      when(stub.readOriginalStartSessionAdmissionTerminal(
              any(ReadOriginalStartSessionAdmissionTerminalRequest.class)))
          .thenReturn(ReadOriginalStartSessionAdmissionTerminalResponse.newBuilder().build());
      stubField.set(client, stub);

      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid original StartSession terminal evidence");
      verify(stub).withDeadlineAfter(5, TimeUnit.SECONDS);
      verify(stub)
          .readOriginalStartSessionAdmissionTerminal(
              OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(request));
      verify(channelFactory)
          .buildChannel(
              eq("game-session.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
      client.close();
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized");
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsEndUserAndAmbientSqlBeforeCallingTheStub(@TempDir Path directory) throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("game-session.internal:6565");
    when(channelFactory.buildChannel(
            eq("game-session.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    var client =
        new GrpcOriginalStartSessionAdmissionTerminalReadClient(
            endpoints, tls(directory), channelFactory, NAMESPACE);
    try {
      client.init();
      var stub =
          mock(
              OriginalStartSessionAdmissionTerminalReadServiceGrpc
                  .OriginalStartSessionAdmissionTerminalReadServiceBlockingStub.class);
      var stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      stubField.setAccessible(true);
      stubField.set(client, stub);

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only");
      SessionContext.clear();

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();
      verifyNoInteractions(stub);
    } finally {
      client.close();
    }
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    Path cert = Files.writeString(directory.resolve("client.crt"), "synthetic certificate");
    Path key = Files.writeString(directory.resolve("client.key"), "synthetic private key");
    Path ca = Files.writeString(directory.resolve("ca.crt"), "synthetic CA");
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(cert.toString());
    tls.setPrivateKey(key.toString());
    tls.setCaCert(ca.toString());
    return tls;
  }
}
