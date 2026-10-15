package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File-backed workload-mTLS policy is enforced before any channel can be built. */
class AuthoredInitialActorCoreReadClientMtlsTest {
  @Test
  void rejectsPlaintextClasspathAndMissingCredentialFilesBeforeChannelCreation(@TempDir Path dir)
      throws Exception {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = fileBackedTls(dir);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    assertThatThrownBy(() -> newClient(new CommonGrpcClientProperties(), factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties classpath = fileBackedTls(dir);
    classpath.setCaCert("classpath:certs/ca.crt");
    assertThatThrownBy(() -> newClient(classpath, factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    missingFiles.setCertChain(dir.resolve("missing-client.crt").toString());
    missingFiles.setPrivateKey(dir.resolve("missing-client.key").toString());
    missingFiles.setCaCert(dir.resolve("missing-ca.crt").toString());
    assertThatThrownBy(() -> newClient(missingFiles, factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readable file");
    verifyNoInteractions(factory);
  }

  private static GrpcAuthoredInitialActorCoreReadClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory factory) {
    return new GrpcAuthoredInitialActorCoreReadClient(
        new ServiceEndpointsProperties(), tls, factory, "test");
  }

  private static CommonGrpcClientProperties fileBackedTls(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
