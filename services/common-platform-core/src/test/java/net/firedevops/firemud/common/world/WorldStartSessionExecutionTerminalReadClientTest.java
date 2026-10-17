package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannelBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadClient.InvalidTerminalEvidenceException;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldStartSessionExecutionTerminalReadServiceGrpc.WorldStartSessionExecutionTerminalReadServiceBlockingStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldStartSessionExecutionTerminalReadClientTest {
  @Test
  void malformedOrMismatchedOwnerResponseHasADistinctEvidenceFailure(@TempDir Path directory)
      throws Exception {
    var client =
        new WorldStartSessionExecutionTerminalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    // Controlled stub responses prove decoding classification, not authenticated transport.
    var stub = mock(WorldStartSessionExecutionTerminalReadServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    ReflectionTestUtils.setField(client, "stub", stub);
    ReflectionTestUtils.setField(client, "initialized", true);
    var request = WorldStartSessionExecutionTerminalReadGrpcCodecTest.request();
    try {
      for (var response :
          List.of(
              ReadWorldStartSessionExecutionTerminalResponse.getDefaultInstance(),
              ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
                  .setReadRequestId(UUID.randomUUID().toString())
                  .build(),
              ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
                  .setReadRequestId(request.readRequestId().toString())
                  .build())) {
        when(stub.readWorldStartSessionExecutionTerminal(any())).thenReturn(response);
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(InvalidTerminalEvidenceException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
      }
    } finally {
      client.close();
    }
  }

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
