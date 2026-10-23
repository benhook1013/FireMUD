package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction ordering units; mocked channels do not establish an mTLS handshake. */
class GameDesignPublicationTerminalClientTransactionTest {
  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresFileBackedMtlsWithGameDesignTerminalContext(@TempDir Path directory) {
    var factory = mock(GrpcChannelFactory.class);
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () -> new GameDesignPublicationTerminalClient(endpoints, plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Game Design publication terminal read")
        .hasNoCause();

    var classpath = new CommonGrpcClientProperties();
    classpath.setPlaintext(false);
    classpath.setCertChain("classpath:client.crt");
    classpath.setPrivateKey(directory.resolve("client.key").toString());
    classpath.setCaCert(directory.resolve("ca.crt").toString());
    assertThatThrownBy(
            () -> new GameDesignPublicationTerminalClient(endpoints, classpath, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Game Design publication terminal read")
        .hasMessageNotContaining("classpath:client.crt")
        .hasNoCause();
    verifyNoInteractions(factory);
  }

  @Test
  void excludesActiveAndSynchronizationOnlyTransactionsBeforeAnyRpc(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("design.internal:6565");
    when(factory.buildChannel(
            eq("design.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(mock(ManagedChannel.class));
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private-key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "ca").toString());
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var request =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", operation.canonicalBytes());
    var expected =
        new GameDesignPublicationTerminalReadEvidence.ReadResult(
            request, GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN, Optional.empty());
    var stub =
        mock(
            GameDesignPublicationTerminalReadServiceGrpc
                .GameDesignPublicationTerminalReadServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.readGameDesignPublicationTerminal(any()))
        .thenReturn(GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, expected));

    try (var client = new GameDesignPublicationTerminalClient(endpoints, tls, factory, "test")) {
      client.init();
      var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      field.set(client, stub);
      assertThat(client.read(request).canonicalBytes()).containsExactly(expected.canonicalBytes());
      clearInvocations(stub);

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires no owner transaction");
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires no owner transaction");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires no owner transaction");
      TransactionSynchronizationManager.clearSynchronization();
      verifyNoInteractions(stub);

      assertThat(client.read(request).canonicalBytes()).containsExactly(expected.canonicalBytes());
    }
  }
}
