package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;

class WorldPublishedSpawnRequirementsClientTest {
  @Test
  void rejectsPlaintextAndInvalidNamespaceBeforeCreatingTransport() {
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    mock(GrpcChannelFactory.class),
                    "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Path.of("missing.crt").toString());
    tls.setPrivateKey(Path.of("missing.key").toString());
    tls.setCaCert(Path.of("missing-ca.crt").toString());
    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsClient(
                    new ServiceEndpointsProperties(), tls, mock(GrpcChannelFactory.class), "Test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
  }
}
