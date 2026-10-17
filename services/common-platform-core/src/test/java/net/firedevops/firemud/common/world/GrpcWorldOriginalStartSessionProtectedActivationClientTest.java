package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS/client lifecycle denial checks only; no live World endpoint is implied. */
class GrpcWorldOriginalStartSessionProtectedActivationClientTest {
  @Test
  void requiresExplicitFileBackedMtlsBeforeAnyTransport(@TempDir Path directory) throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = fileBacked(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcWorldOriginalStartSessionProtectedActivationClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    CommonGrpcClientProperties classpath = new CommonGrpcClientProperties();
    classpath.setCertChain("classpath:client.crt");
    classpath.setPrivateKey("/tmp/key");
    classpath.setCaCert("/tmp/ca");
    assertThatThrownBy(
            () ->
                new GrpcWorldOriginalStartSessionProtectedActivationClient(
                    new ServiceEndpointsProperties(), classpath, factory, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
    verifyNoInteractions(factory);
  }

  @Test
  void requiresInitializationAndNoAmbientGameSessionTransaction(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    var client =
        new GrpcWorldOriginalStartSessionProtectedActivationClient(
            new ServiceEndpointsProperties(), fileBacked(directory), factory, "world-runtime");
    try {
      doNothing().when(input).requireTargetNamespace("world-runtime");
      assertThatThrownBy(() -> client.activate(input))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.activate(input))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient Game Session transaction");
      verifyNoInteractions(factory);
    } finally {
      TransactionSynchronizationManager.clear();
      client.close();
    }
  }

  private static CommonGrpcClientProperties fileBacked(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
