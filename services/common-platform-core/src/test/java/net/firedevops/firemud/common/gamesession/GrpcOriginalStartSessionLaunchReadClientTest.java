package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GrpcOriginalStartSessionLaunchReadClientTest {
  private GrpcOriginalStartSessionLaunchReadClient client;

  @AfterEach
  void closeClientAndClearTransactionContext() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresFileBackedMtlsExplicitInitializationAndNoAmbientTransaction(@TempDir Path dir)
      throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = fileBackedMtls(dir);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, factory, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var request = OriginalStartSessionLaunchReadEvidenceTest.requestForClientTest();
    client = newClient(fileBackedMtls(dir), factory, request.selector().targetNamespace());
    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    verifyNoInteractions(factory);
  }

  @Test
  void rejectsAnotherConfiguredNamespaceBeforeTransport(@TempDir Path dir) throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    var request = OriginalStartSessionLaunchReadEvidenceTest.requestForClientTest();
    String otherNamespace =
        "other-runtime".equals(request.selector().targetNamespace())
            ? "different-runtime"
            : "other-runtime";
    client = newClient(fileBackedMtls(dir), factory, otherNamespace);

    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    verifyNoInteractions(factory);
  }

  private GrpcOriginalStartSessionLaunchReadClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory factory, String namespace) {
    return new GrpcOriginalStartSessionLaunchReadClient(
        new ServiceEndpointsProperties(), tls, factory, namespace);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
