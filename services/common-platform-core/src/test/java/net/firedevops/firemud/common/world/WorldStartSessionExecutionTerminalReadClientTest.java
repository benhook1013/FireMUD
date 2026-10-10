package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannelBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldStartSessionExecutionTerminalReadClientTest {
  @Test
  void clientReceiveLimitIncludesTheCompleteCanonicalTerminalEnvelope(@TempDir Path directory)
      throws Exception {
    var client =
        new WorldStartSessionExecutionTerminalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    // No connection is made: this verifies stub configuration, not authenticated socket proof.
    var channel = ManagedChannelBuilder.forTarget("localhost:1").usePlaintext().build();
    try {
      assertThat(client.buildStub(channel).getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(WorldStartSessionExecutionTerminalReadGrpcCodec.MAX_RESPONSE_BYTES)
          .isGreaterThan(WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES);
    } finally {
      channel.shutdownNow();
      client.close();
    }
  }

  @Test
  void requiresFileBackedMtlsInitializationAndAnIndependentCallerTransaction(
      @TempDir Path directory) throws Exception {
    var plaintext = fileBacked(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminalReadClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = fileBacked(directory);
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminalReadClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    var client =
        new WorldStartSessionExecutionTerminalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    try {
      assertThatThrownBy(() -> client.read(null)).isInstanceOf(NullPointerException.class);
      var request = WorldStartSessionExecutionTerminalReadGrpcCodecTest.request();
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized");

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
    } finally {
      TransactionSynchronizationManager.clear();
      client.close();
    }
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
