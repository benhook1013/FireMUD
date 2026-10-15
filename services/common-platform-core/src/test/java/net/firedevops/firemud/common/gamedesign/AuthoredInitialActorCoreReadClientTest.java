package net.firedevops.firemud.common.gamedesign;

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
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthoredInitialActorCoreReadClientTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearTransactionFlags() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void requiresExplicitInitializationExactNamespaceAndNoAmbientOwnerTransaction(@TempDir Path dir)
      throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    var client = newClient(fileBackedTls(dir), factory);
    var request = new ReadRequest(AuthoredInitialActorCoreReadTestFixture.request());

    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThatThrownBy(
            () ->
                client.read(
                    new ReadRequest(
                        AuthoredInitialActorCoreReadTestFixture.request("other"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("client identity");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient transaction");
    verifyNoInteractions(factory);
  }

  @Test
  void initializesOnlyTheExplicitGameDesignEndpoint(@TempDir Path dir) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    CommonGrpcClientProperties tls = fileBackedTls(dir);
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    var client =
        new GrpcAuthoredInitialActorCoreReadClient(endpoints, tls, factory, NAMESPACE);

    try {
      client.init();
      verify(factory)
          .buildChannel(
              eq("game-design.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static GrpcAuthoredInitialActorCoreReadClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory factory) {
    return new GrpcAuthoredInitialActorCoreReadClient(
        new ServiceEndpointsProperties(), tls, factory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedTls(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
