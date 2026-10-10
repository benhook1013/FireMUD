package net.firedevops.firemud.common.security.sourceintake;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Constructor and pre-transport checks only; placeholder files do not prove an mTLS handshake. */
class GrpcSelectedOwnerIntakeSettlementClientTest {
  @AfterEach
  void clearThreadContext() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void requiresFileBackedMtlsAndCanonicalNamespace(@TempDir Path directory) throws IOException {
    var channelFactory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSettlementClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    for (String invalidPath :
        new String[] {
          null,
          "",
          "classpath:credential.pem",
          directory.toString(),
          directory.resolve("missing.pem").toString()
        }) {
      var invalidCertificate = tls(directory);
      invalidCertificate.setCertChain(invalidPath);
      assertThatThrownBy(
              () ->
                  new GrpcSelectedOwnerIntakeSettlementClient(
                      new ServiceEndpointsProperties(), invalidCertificate, channelFactory, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var invalidKey = tls(directory);
    invalidKey.setPrivateKey(directory.resolve("missing-key.pem").toString());
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSettlementClient(
                    new ServiceEndpointsProperties(), invalidKey, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var invalidCa = tls(directory);
    invalidCa.setCaCert(directory.resolve("missing-ca.pem").toString());
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSettlementClient(
                    new ServiceEndpointsProperties(), invalidCa, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSettlementClient(
                    new ServiceEndpointsProperties(), tls(directory), channelFactory, "Test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsAmbientSqlSynchronizationAndEndUserContextBeforeTransport(@TempDir Path directory)
      throws IOException {
    var channelFactory = mock(GrpcChannelFactory.class);
    var client =
        new GrpcSelectedOwnerIntakeSettlementClient(
            new ServiceEndpointsProperties(), tls(directory), channelFactory, "test");
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.settle(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.settle(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.settle(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only context");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
      TransactionSynchronizationManager.clear();
      SessionContext.clear();
    }
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "placeholder cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "placeholder key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "placeholder CA").toString());
    return tls;
  }
}
