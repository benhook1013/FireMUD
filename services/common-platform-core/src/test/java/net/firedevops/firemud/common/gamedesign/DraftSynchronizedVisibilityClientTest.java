package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftSynchronizedVisibilityClientTest {
  @Test
  void rejectsPlaintextBeforeChannelCreation(@TempDir Path dir) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties tls = tls(dir);
    tls.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new DraftSynchronizedVisibilityClient(
                    new ServiceEndpointsProperties(), tls, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void requiresExplicitInitializationAndRejectsOtherNamespacesBeforeChannelUse(@TempDir Path dir)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    DraftSynchronizedVisibilityClient client =
        new DraftSynchronizedVisibilityClient(
            new ServiceEndpointsProperties(), tls(dir), channelFactory, "test");
    try {
      assertThatThrownBy(() -> client.read(request("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      assertThatThrownBy(() -> client.read(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesMtlSStubForConfiguredGameDesignTarget(@TempDir Path dir) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    DraftSynchronizedVisibilityClient client =
        new DraftSynchronizedVisibilityClient(endpoints, tls(dir), channelFactory, "test");
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-design.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static DraftSynchronizedVisibilityEvidence.Request request(String namespace) {
    var target =
        new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    return new DraftSynchronizedVisibilityEvidence.Request(
        1, namespace, UUID.fromString("33333333-3333-4333-8333-333333333333"), target);
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
