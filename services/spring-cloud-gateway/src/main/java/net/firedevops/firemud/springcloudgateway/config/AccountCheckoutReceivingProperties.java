package net.firedevops.firemud.springcloudgateway.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Data;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in configuration for the isolated Account checkout receiving surface. */
@Data
@ConfigurationProperties(prefix = "firemud.account.checkout-receiving")
public class AccountCheckoutReceivingProperties {
  private boolean enabled;
  private String workloadNamespace;
  private String accountHttpsBaseUrl;
  private String accountCaCertPath;
  private int connectTimeoutMs = 2_000;
  private int responseTimeoutMs = 5_000;
  private int rateLimitDecisionTimeoutMs = 2_000;

  /** Rejects any enabled configuration that cannot establish a verified HTTPS upstream. */
  public void validateEnabledConfiguration() {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalStateException(
          "Checkout receiving requires one canonical Gateway workload namespace");
    }
    URI upstream;
    try {
      upstream = URI.create(accountHttpsBaseUrl);
    } catch (RuntimeException malformed) {
      throw new IllegalStateException("Checkout receiving requires an HTTPS Account upstream");
    }
    if (!"https".equals(upstream.getScheme())
        || upstream.getHost() == null
        || upstream.getHost().isBlank()
        || upstream.getUserInfo() != null
        || upstream.getQuery() != null
        || upstream.getFragment() != null
        || (upstream.getRawPath() != null
            && !upstream.getRawPath().isEmpty()
            && !"/".equals(upstream.getRawPath()))
        || (upstream.getPort() != -1 && (upstream.getPort() < 1 || upstream.getPort() > 65_535))) {
      throw new IllegalStateException(
          "Checkout receiving requires an authority-only HTTPS Account upstream");
    }
    if (accountCaCertPath == null || accountCaCertPath.isBlank()) {
      throw new IllegalStateException("Checkout receiving requires an explicit Account HTTPS CA");
    }
    Path caPath;
    try {
      if (accountCaCertPath.startsWith("classpath:")) {
        throw new IllegalArgumentException("filesystem CA required");
      }
      caPath = Path.of(accountCaCertPath);
    } catch (RuntimeException malformed) {
      throw new IllegalStateException(
          "Checkout receiving requires readable file-backed Account HTTPS CA material");
    }
    if (!Files.isRegularFile(caPath) || !Files.isReadable(caPath)) {
      throw new IllegalStateException(
          "Checkout receiving requires readable file-backed Account HTTPS CA material");
    }
    if (connectTimeoutMs < 1
        || connectTimeoutMs > 30_000
        || responseTimeoutMs < 1
        || responseTimeoutMs > 30_000
        || rateLimitDecisionTimeoutMs < 1
        || rateLimitDecisionTimeoutMs > 30_000) {
      throw new IllegalStateException("Checkout receiving requires bounded positive timeouts");
    }
  }

  public URI accountHttpsBaseUri() {
    return URI.create(accountHttpsBaseUrl);
  }

  public Path accountCaCertPathAsPath() {
    return Path.of(accountCaCertPath);
  }
}
