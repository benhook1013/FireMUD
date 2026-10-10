package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedGrpcMtlsPolicyTest {
  private static final String PURPOSE = "test read";
  private static final String NULL_CONFIGURATION_MESSAGE = "gRPC mTLS configuration is required";
  private static final String PLAINTEXT_MESSAGE = "test read requires workload mTLS";

  @Test
  void acceptsReadableFileBackedMtlsMaterial(@TempDir Path directory) throws Exception {
    CommonGrpcClientProperties properties = propertiesWithFiles(directory);

    assertThat(require(properties)).isSameAs(properties);
  }

  @Test
  void rejectsMissingOrPlaintextConfiguration() {
    assertThatThrownBy(() -> require(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(NULL_CONFIGURATION_MESSAGE);

    CommonGrpcClientProperties plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> require(plaintext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(PLAINTEXT_MESSAGE);
  }

  @Test
  void rejectsMissingClasspathInvalidDirectoryAndNonexistentMaterial(@TempDir Path directory)
      throws Exception {
    for (String material : new String[] {"certChain", "privateKey", "caCert"}) {
      CommonGrpcClientProperties missingProperties = propertiesWithFiles(directory);
      setPath(missingProperties, material, null);
      assertThatThrownBy(() -> require(missingProperties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("file-backed");
    }

    CommonGrpcClientProperties classpathProperties = propertiesWithFiles(directory);
    classpathProperties.setCaCert("classpath:ca.crt");
    assertThatThrownBy(() -> require(classpathProperties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties invalidPathProperties = propertiesWithFiles(directory);
    invalidPathProperties.setPrivateKey("\u0000sensitive-path");
    assertThatThrownBy(() -> require(invalidPathProperties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("private key")
        .hasMessageNotContaining("sensitive-path");

    CommonGrpcClientProperties directoryProperties = propertiesWithFiles(directory);
    directoryProperties.setCaCert(directory.toString());
    assertThatThrownBy(() -> require(directoryProperties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file")
        .hasMessageNotContaining(directory.toString());

    CommonGrpcClientProperties missingFileProperties = propertiesWithFiles(directory);
    String missingPath = directory.resolve("sensitive-missing-ca.crt").toString();
    missingFileProperties.setCaCert(missingPath);
    assertThatThrownBy(() -> require(missingFileProperties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file")
        .hasMessageNotContaining(missingPath);
  }

  private static CommonGrpcClientProperties require(CommonGrpcClientProperties properties) {
    return FileBackedGrpcMtlsPolicy.require(
        properties, PURPOSE, NULL_CONFIGURATION_MESSAGE, PLAINTEXT_MESSAGE);
  }

  private static CommonGrpcClientProperties propertiesWithFiles(Path directory) throws Exception {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(Files.createTempFile(directory, "client-cert", ".pem").toString());
    properties.setPrivateKey(Files.createTempFile(directory, "client-key", ".pem").toString());
    properties.setCaCert(Files.createTempFile(directory, "client-ca", ".pem").toString());
    return properties;
  }

  private static void setPath(CommonGrpcClientProperties properties, String material, String path) {
    switch (material) {
      case "certChain" -> properties.setCertChain(path);
      case "privateKey" -> properties.setPrivateKey(path);
      case "caCert" -> properties.setCaCert(path);
      default -> throw new IllegalArgumentException("Unknown test material");
    }
  }
}
