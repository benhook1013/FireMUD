package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
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

  @Test
  void acceptsResolvedReadableFiles(@TempDir Path directory) throws Exception {
    CommonGrpcClientProperties properties = propertiesWithFiles(directory);

    assertThatCode(
            () ->
                requireResolved(
                    properties, new GrpcTlsMaterialResolver(), "test read requires workload mTLS"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsResolvedClasspathMaterialAndDirectories(@TempDir Path directory) throws Exception {
    CommonGrpcClientProperties properties = propertiesWithFiles(directory);
    GrpcTlsMaterialResolver classpathResolver =
        new GrpcTlsMaterialResolver() {
          @Override
          public ResolvedGrpcTlsMaterial resolve(CommonGrpcClientProperties ignored) {
            ResolvedGrpcTlsMaterial.TlsResource classpathResource =
                new ResolvedGrpcTlsMaterial.TlsResource(() -> InputStream.nullInputStream(), null);
            return new ResolvedGrpcTlsMaterial(
                classpathResource, classpathResource, classpathResource);
          }
        };
    assertThatThrownBy(
            () ->
                requireResolved(properties, classpathResolver, "test read requires workload mTLS"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test read requires readable file-backed workload mTLS");

    CommonGrpcClientProperties directoryProperties = propertiesWithFiles(directory);
    directoryProperties.setCaCert(directory.toString());
    assertThatThrownBy(
            () ->
                requireResolved(
                    directoryProperties,
                    new GrpcTlsMaterialResolver(),
                    "test read requires workload mTLS"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test read requires readable file-backed workload mTLS");
  }

  @Test
  void rejectsPlaintextAndResolverFailuresWithPurposeSpecificMessages(@TempDir Path directory)
      throws Exception {
    CommonGrpcClientProperties plaintext = propertiesWithFiles(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                requireResolved(
                    plaintext,
                    new GrpcTlsMaterialResolver(),
                    "StartSession authorization requires file-backed workload mTLS"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("StartSession authorization requires file-backed workload mTLS");

    GrpcTlsMaterialResolver failingResolver =
        new GrpcTlsMaterialResolver() {
          @Override
          public ResolvedGrpcTlsMaterial resolve(CommonGrpcClientProperties ignored)
              throws IOException {
            throw new IOException("sensitive resolver detail");
          }
        };
    assertThatThrownBy(
            () ->
                requireResolved(
                    propertiesWithFiles(directory),
                    failingResolver,
                    "Account authorization requires workload mTLS"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test read requires readable file-backed workload mTLS")
        .hasMessageNotContaining("sensitive resolver detail");
  }

  private static CommonGrpcClientProperties require(CommonGrpcClientProperties properties) {
    return FileBackedGrpcMtlsPolicy.require(
        properties, PURPOSE, NULL_CONFIGURATION_MESSAGE, PLAINTEXT_MESSAGE);
  }

  private static void requireResolved(
      CommonGrpcClientProperties properties,
      GrpcTlsMaterialResolver resolver,
      String plaintextMessage) {
    FileBackedGrpcMtlsPolicy.require(properties, resolver, "test read", plaintextMessage);
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
