package net.firedevops.firemud.common.security.sourceintake;

import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerCredentials;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderCredentials;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeAuthorizationCredentials;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.security.SessionContext;

/** Shared file-backed workload mTLS validation for selected-source intake clients. */
final class SelectedSourceIntakeClientTls {
  private SelectedSourceIntakeClientTls() {}

  static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties, String clientLabel) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(clientLabel + " requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), clientLabel, "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), clientLabel, "private key");
    requireReadableFile(tlsProperties.getCaCert(), clientLabel, "CA certificate");
    return tlsProperties;
  }

  static void requireWorkloadOnlyContext(String clientLabel) {
    if (SessionContext.hasAuthenticatedCallerContext()
        || AccountOriginalDraftOrderCredentials.CONTEXT_KEY.get() != null
        || AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY.get() != null
        || SelectedOwnerIntakeAuthorizationProducerCredentials.CONTEXT_KEY.get() != null) {
      throw new IllegalStateException(clientLabel + " requires workload-only context");
    }
  }

  private static void requireReadableFile(String configuredPath, String clientLabel, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          clientLabel + " requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid " + clientLabel + " " + label + " path");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          clientLabel + " " + label + " must be an existing readable file");
    }
  }
}
