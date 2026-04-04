package net.firedevops.firemud.common.runtime;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;

/** Resolves one runtime identity snapshot for the current service instance. */
public class RuntimeIdentityFactory {

  public RuntimeIdentity create(
      Environment environment,
      String serviceName,
      BuildProperties buildProperties,
      GitProperties gitProperties) {
    String hostname = firstNonBlank(environment.getProperty("HOSTNAME"), localHostname());
    String serviceInstanceId =
        firstNonBlank(
            environment.getProperty("KUBERNETES_POD_NAME"),
            environment.getProperty("POD_NAME"),
            hostname,
            UUID.randomUUID().toString());
    String buildVersion =
        firstNonBlank(
            buildProperties != null ? buildProperties.getVersion() : null,
            environment.getProperty("FIREMUD_BUILD_VERSION"));
    String buildSha =
        firstNonBlank(
            gitProperties != null ? gitProperties.getCommitId() : null,
            environment.getProperty("FIREMUD_BUILD_SHA"));
    String imageTag = firstNonBlank(environment.getProperty("FIREMUD_IMAGE_TAG"));
    return new RuntimeIdentity(
        firstNonBlank(serviceName, "unknown-service"),
        serviceInstanceId,
        hostname,
        Instant.now(),
        buildVersion,
        buildSha,
        imageTag);
  }

  private static String localHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
