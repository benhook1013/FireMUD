package net.firedevops.firemud.common.grpc;

import java.nio.file.Files;
import java.nio.file.Path;

/** Validates the file-backed client credentials required by selected internal mTLS reads. */
public final class FileBackedGrpcMtlsPolicy {
  private FileBackedGrpcMtlsPolicy() {}

  /** Requires non-plaintext gRPC TLS configured with three existing, readable filesystem files. */
  public static CommonGrpcClientProperties require(
      CommonGrpcClientProperties tlsProperties,
      String purpose,
      String missingConfigurationMessage,
      String plaintextMessage) {
    if (tlsProperties == null) {
      throw new IllegalArgumentException(missingConfigurationMessage);
    }
    if (tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(plaintextMessage);
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain", purpose);
    requireReadableFile(tlsProperties.getPrivateKey(), "private key", purpose);
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate", purpose);
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label, String purpose) {
    String materialMessage = purpose + " requires file-backed certificate, key, and CA material";
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(materialMessage);
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(materialMessage);
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          purpose + " " + label + " must be a readable file-backed path");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          purpose + " " + label + " must be an existing readable file");
    }
  }
}
