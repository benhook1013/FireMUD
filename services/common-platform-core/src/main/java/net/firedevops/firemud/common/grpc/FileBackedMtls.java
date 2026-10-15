package net.firedevops.firemud.common.grpc;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared validation for gRPC clients that require file-backed workload mTLS material. */
public final class FileBackedMtls {
  private FileBackedMtls() {}

  public static CommonGrpcClientProperties require(
      CommonGrpcClientProperties tlsProperties, String clientLabel) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(clientLabel + " requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain", clientLabel);
    requireReadableFile(tlsProperties.getPrivateKey(), "private key", clientLabel);
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate", clientLabel);
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label, String clientLabel) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          clientLabel + " requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          clientLabel + " requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          clientLabel + " " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          clientLabel + " " + label + " must be an existing readable file");
    }
  }
}
