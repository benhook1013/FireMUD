package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Configuration and call-boundary proof only; this is not socket or peer-authentication proof. */
class AccountStartSessionWorldParticipationHistoricalReadClientTest {
  @Test
  void requiresFileBackedMtlsAndConfiguredNamespace(@TempDir Path directory) throws Exception {
    var plaintext = fileBacked(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = fileBacked(directory);
    classpath.setPrivateKey("classpath:private.key");
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    var client =
        new AccountStartSessionWorldParticipationHistoricalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    try {
      assertThatThrownBy(
              () ->
                  new AccountStartSessionWorldParticipationHistoricalReadClient(
                      new ServiceEndpointsProperties(),
                      fileBacked(directory),
                      new GrpcChannelFactory(),
                      "not a namespace"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace");
      assertThatThrownBy(() -> client.read(request("other-runtime")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
    } finally {
      client.close();
    }
  }

  @Test
  void requiresInitializedClientAndNoAmbientSqlOrSynchronization(@TempDir Path directory)
      throws Exception {
    var client =
        new AccountStartSessionWorldParticipationHistoricalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    try {
      assertThatThrownBy(() -> client.read(request("world-runtime")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized");

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(request("world-runtime")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.read(request("world-runtime")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
    } finally {
      TransactionSynchronizationManager.clear();
      client.close();
    }
  }

  private static AccountStartSessionWorldParticipationHistoricalReadRequest request(
      String namespace) {
    return new AccountStartSessionWorldParticipationHistoricalReadRequest(
        UUID.fromString("d1f41bfb-265a-4f3b-8b2a-124cba20ce43"),
        namespace,
        UUID.fromString("a8c1e8c8-f237-41b7-918d-ec2281bcac10"),
        31L);
  }

  private static CommonGrpcClientProperties fileBacked(Path directory) throws Exception {
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    properties.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "private key").toString());
    properties.setCaCert(
        Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return properties;
  }
}
