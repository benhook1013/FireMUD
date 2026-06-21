package net.firedevops.firemud.common.grpc;

import java.util.Locale;
import java.util.Optional;

/** Shared toggle for environments that intentionally disable TLS file watching and hot reload. */
public final class GrpcTlsReloadPolicy {
  static final String PROPERTY_NAME = "firemud.grpc.tls-reload.enabled";
  static final String ENV_NAME = "FIREMUD_GRPC_TLS_RELOAD_ENABLED";

  private GrpcTlsReloadPolicy() {}

  public static boolean isEnabled() {
    return configuredValue().map(GrpcTlsReloadPolicy::parseEnabled).orElse(true);
  }

  static Optional<String> configuredValue() {
    String propertyValue = System.getProperty(PROPERTY_NAME);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return Optional.of(propertyValue);
    }

    String envValue = System.getenv(ENV_NAME);
    if (envValue != null && !envValue.isBlank()) {
      return Optional.of(envValue);
    }

    return Optional.empty();
  }

  private static boolean parseEnabled(String rawValue) {
    String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
    return !normalized.equals("false")
        && !normalized.equals("0")
        && !normalized.equals("off")
        && !normalized.equals("disabled")
        && !normalized.equals("no");
  }
}
