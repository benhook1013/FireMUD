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

class AuthoredWorldVersionStateClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void rejectsPlaintextClasspathAndUnreadableTlsBeforeChannelCreation(@TempDir Path directory)
      throws Exception {
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
    classpath.setPrivateKey("classpath:certs/client.key");
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
  void requiresExplicitInitializationAndRejectsOtherNamespaces(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AuthoredWorldVersionStateClient client = newClient(fileBackedMtls(directory), channelFactory);
    try {
      assertThatThrownBy(() -> client.read(request()))
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
  void initializesOnlyConfiguredTargetFromFileBackedTls(@TempDir Path directory) throws Exception {
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
    AuthoredWorldVersionStateClient client =
        new AuthoredWorldVersionStateClient(endpoints, tls, channelFactory, NAMESPACE);

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

  private static AuthoredWorldVersionStateClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory) {
    return new AuthoredWorldVersionStateClient(
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

  private static AuthoredWorldVersionStateEvidence.Request request() {
    return request(NAMESPACE);
  }

  private static AuthoredWorldVersionStateEvidence.Request request(String namespace) {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        namespace,
        READ_REQUEST_ID,
        TENANT_ID,
        "cafe-coast",
        SOURCE_OPERATION_ID,
        "sha256:" + "a".repeat(64),
        19L);
  }
}
