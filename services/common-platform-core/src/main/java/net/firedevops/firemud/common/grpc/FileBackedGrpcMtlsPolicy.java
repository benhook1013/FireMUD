package net.firedevops.firemud.common.grpc;

import java.io.IOException;
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

  /**
   * Requires non-plaintext gRPC TLS to resolve to three readable filesystem-backed resources.
   *
   * <p>The supplied plaintext message keeps the existing caller-specific failure classification.
   */
  public static void require(
      CommonGrpcClientProperties tlsProperties,
      GrpcTlsMaterialResolver resolver,
      String purpose,
      String plaintextMessage) {
    if (tlsProperties == null || resolver == null) {
      throw new IllegalStateException(purpose + " requires file-backed workload mTLS");
    }
    if (tlsProperties.isPlaintext()) {
      throw new IllegalStateException(plaintextMessage);
    }

    ResolvedGrpcTlsMaterial material;
    try {
      material = resolver.resolve(tlsProperties);
    } catch (IOException | RuntimeException failure) {
      throw readableMaterialRequired(purpose);
    }
    if (material == null
        || !isReadableFile(material.certChain())
        || !isReadableFile(material.privateKey())
        || !isReadableFile(material.caCert())) {
      throw readableMaterialRequired(purpose);
    }
  }

  private static IllegalStateException readableMaterialRequired(String purpose) {
    return new IllegalStateException(purpose + " requires readable file-backed workload mTLS");
  }

  private static boolean isReadableFile(ResolvedGrpcTlsMaterial.TlsResource resource) {
    Path path = resource == null ? null : resource.watchPath();
    return path != null && Files.isRegularFile(path) && Files.isReadable(path);
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
