package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

/** Client construction and boundary denials; no remote Account call or TLS identity proof. */
class GrpcAccountStartSessionActivationProtectionAcquisitionClientTest {
  @TempDir Path temporaryDirectory;

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsPlaintextClasspathAndMissingMaterial() {
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> client(plaintext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = new CommonGrpcClientProperties();
    classpath.setCertChain("classpath:client.pem");
    classpath.setPrivateKey("/tmp/key.pem");
    classpath.setCaCert("/tmp/ca.pem");
    assertThatThrownBy(() -> client(classpath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    assertThatThrownBy(() -> client(new CommonGrpcClientProperties()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
  }

  @Test
  void rejectsUninitializedUseAndAnyAmbientTransactionBeforeRequestHandling() throws IOException {
    var client = client(fileBackedProperties());
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    TransactionSynchronizationManager.clearSynchronization();
  }

  private GrpcAccountStartSessionActivationProtectionAcquisitionClient client(
      CommonGrpcClientProperties tls) {
    return new GrpcAccountStartSessionActivationProtectionAcquisitionClient(
        new ServiceEndpointsProperties(), tls, mock(GrpcChannelFactory.class), "world-runtime");
  }

  private CommonGrpcClientProperties fileBackedProperties() throws IOException {
    Path certificate = Files.createFile(temporaryDirectory.resolve("client.pem"));
    Path privateKey = Files.createFile(temporaryDirectory.resolve("client-key.pem"));
    Path ca = Files.createFile(temporaryDirectory.resolve("ca.pem"));
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(certificate.toString());
    properties.setPrivateKey(privateKey.toString());
    properties.setCaCert(ca.toString());
    return properties;
  }
}
