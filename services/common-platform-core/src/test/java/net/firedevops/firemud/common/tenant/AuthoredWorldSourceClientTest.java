package net.firedevops.firemud.common.tenant;

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

class AuthoredWorldSourceClientTest {
  private static final String NAMESPACE = "test";
  private static final AuthoredWorldSourceGrpcCodec.ReadRequest REQUEST =
      new AuthoredWorldSourceGrpcCodec.ReadRequest(
          NAMESPACE,
          UUID.fromString("12345678-1234-4234-8234-123456789abc"),
          UUID.fromString("22345678-1234-4234-8234-123456789abc"),
          UUID.fromString("32345678-1234-4234-8234-123456789abc"),
          "world-one");

  @Test
  void rejectsPlaintextMissingClasspathAndUnreadableTlsMaterialBeforeChannelCreation(
      @TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);

    CommonGrpcClientProperties plaintext = fileBackedMtls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    assertThatThrownBy(() -> newClient(new CommonGrpcClientProperties(), channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties classpath = fileBackedMtls(directory);
    classpath.setCaCert("classpath:certs/ca.crt");
    assertThatThrownBy(() -> newClient(classpath, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    missingFiles.setCertChain(directory.resolve("missing-client.crt").toString());
    missingFiles.setPrivateKey(directory.resolve("missing-client.key").toString());
    missingFiles.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(() -> newClient(missingFiles, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void requiresExplicitInitializationAndRejectsNamespaceMismatch(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AuthoredWorldSourceClient client = newClient(fileBackedMtls(directory), channelFactory);
    try {
      assertThatThrownBy(() -> client.read(REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      assertThatThrownBy(
              () ->
                  client.read(
                      new AuthoredWorldSourceGrpcCodec.ReadRequest(
                          "other",
                          REQUEST.requestId(),
                          REQUEST.operationId(),
                          REQUEST.canonicalTenantId(),
                          REQUEST.worldSlug())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesTheConfiguredGameDesignTargetOnlyOnExplicitInit(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    CommonGrpcClientProperties tls = fileBackedMtls(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    AuthoredWorldSourceClient client =
        new AuthoredWorldSourceClient(endpoints, tls, channelFactory, NAMESPACE);

    try {
      client.init();
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

  private static AuthoredWorldSourceClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory) {
    return new AuthoredWorldSourceClient(
        new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
