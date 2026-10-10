package net.firedevops.firemud.common.gamesession;

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
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.Request;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HistoricalOriginalStartSessionOwnerEvidenceReadClientTest {
  private static final String NAMESPACE = "gameplay";

  private HistoricalOriginalStartSessionOwnerEvidenceReadClient client;

  @AfterEach
  void closeClientAndClearTransactionContext() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresFileBackedMtlsAndDoesNotInitializeOrReadWithoutExplicitInit(@TempDir Path dir)
      throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = fileBackedMtls(dir);
    plaintext.setPlaintext(true);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> newClient(plaintext, factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    client = newClient(fileBackedMtls(dir), factory);
    assertThatThrownBy(() -> client.read(request(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.read(request("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.read(request(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    verifyNoInteractions(factory);
  }

  @Test
  void initializesOnlyTheConfiguredGameSessionTargetWithFileBackedMtls(@TempDir Path dir)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("game-session.internal:6565");
    CommonGrpcClientProperties tls = fileBackedMtls(dir);
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("game-session.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    client =
        new HistoricalOriginalStartSessionOwnerEvidenceReadClient(
            endpoints, tls, factory, NAMESPACE);

    client.init();

    verify(factory)
        .buildChannel(
            eq("game-session.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true));
  }

  private HistoricalOriginalStartSessionOwnerEvidenceReadClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory factory) {
    return new HistoricalOriginalStartSessionOwnerEvidenceReadClient(
        new ServiceEndpointsProperties(), tls, factory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static Request request(String namespace) {
    return new Request(
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            namespace,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "world",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "start-session-request",
            "launch-descriptor",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64)),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        9L);
  }
}
