package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Construction and local boundary denials; no authenticated Account transport proof. */
class GrpcAccountStartSessionActivationProtectionSettlementClientTest {
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
  void rejectsUninitializedUseAmbientTransactionsAndChangedNamespace() throws IOException {
    var client = client(fileBackedProperties());
    assertThatThrownBy(() -> client.settle(input("world-runtime")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThatThrownBy(() -> client.settle(input("other-runtime")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.settle(input("world-runtime")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient World transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.settle(input("world-runtime")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient World transaction");
    TransactionSynchronizationManager.clearSynchronization();
  }

  private GrpcAccountStartSessionActivationProtectionSettlementClient client(
      CommonGrpcClientProperties tls) {
    return new GrpcAccountStartSessionActivationProtectionSettlementClient(
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

  private static AccountStartSessionActivationProtectionSettlementInput input(String namespace) {
    return new AccountStartSessionActivationProtectionSettlementInput(
        namespace, UUID.randomUUID(), UUID.randomUUID(), 1L);
  }
}
