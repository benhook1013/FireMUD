package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * mTLS configuration denial checks only; no live World endpoint or authenticated peer is implied.
 */
class GrpcWorldOriginalStartSessionActivationTerminalReadClientTest {
  @Test
  void requiresExplicitFileBackedMtlsBeforeAnyTransport(@TempDir Path directory) throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = fileBacked(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcWorldOriginalStartSessionActivationTerminalReadClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    CommonGrpcClientProperties classpath = new CommonGrpcClientProperties();
    classpath.setCertChain("classpath:client.crt");
    classpath.setPrivateKey("/tmp/key");
    classpath.setCaCert("/tmp/ca");
    assertThatThrownBy(
            () ->
                new GrpcWorldOriginalStartSessionActivationTerminalReadClient(
                    new ServiceEndpointsProperties(), classpath, factory, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
    verifyNoInteractions(factory);
  }

  @Test
  void requiresExplicitInitializationAndNoAmbientAccountSql(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    var evidence =
        mock(
            AccountStartSessionActivationProtectionEvidence.class,
            org.mockito.Mockito.withSettings()
                .defaultAnswer(org.mockito.Mockito.RETURNS_DEEP_STUBS));
    when(evidence
            .request()
            .originalTuple()
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace())
        .thenReturn("world-runtime");
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(UUID.randomUUID(), evidence);
    var client =
        new GrpcWorldOriginalStartSessionActivationTerminalReadClient(
            new ServiceEndpointsProperties(), fileBacked(directory), factory, "world-runtime");
    try {
      assertThatThrownBy(() -> client.read(input))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(input))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient Account transaction");
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
