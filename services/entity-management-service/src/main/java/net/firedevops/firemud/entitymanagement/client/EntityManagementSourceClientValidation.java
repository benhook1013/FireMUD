package net.firedevops.firemud.entitymanagement.client;

import java.util.UUID;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.entitymanagement.validation.CanonicalEntitySourceValues;

/** Narrow validation shared by the passive Entity Management source clients. */
final class EntityManagementSourceClientValidation {
  private EntityManagementSourceClientValidation() {}

  static CommonGrpcClientProperties requireEntityManagementMtls(
      CommonGrpcClientProperties tlsProps, String purpose) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Entity Management gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(purpose + " require Entity Management workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          purpose + " require Entity Management certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          purpose + " require file-backed Entity Management workload mTLS");
    }
    return tlsProps;
  }

  static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    try {
      return CanonicalEntitySourceValues.parseCanonicalNonNilUuid(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Canonical nonnil " + label + " is required",
          exception.getCause() == null ? exception : exception.getCause());
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
