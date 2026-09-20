package net.firedevops.firemud.hostedidentity.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentIdentityPlanner {
  private static final Pattern PREVIEW_NAME = Pattern.compile("pr-[1-9][0-9]{0,50}");
  private static final String DEV_DEMO_NAME = "dev-demo";
  private static final List<String> GRPC_CONSUMERS =
      List.of(
          "account-service",
          "game-session-service",
          "logging-admin-service",
          "social-groups-service",
          "spring-cloud-gateway",
          "tcp-proxy-service");

  private final HostedIdentityProperties properties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected configuration is application-scoped and is never exposed.")
  public EnvironmentIdentityPlanner(HostedIdentityProperties properties) {
    this.properties = properties;
  }

  public EnvironmentIdentityPlan plan(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("HostedEnvironmentIdentity name is required");
    }
    String runtimeNamespace;
    String identityNamespace;
    String hostname;
    if (PREVIEW_NAME.matcher(name).matches()) {
      runtimeNamespace = name;
      identityNamespace = name + "-identity";
      hostname = name + "." + properties.getPreviewDomain();
    } else if (DEV_DEMO_NAME.equals(name)) {
      runtimeNamespace = "dev";
      identityNamespace = "dev-identity";
      hostname = properties.getDevDemoHostname();
    } else {
      throw new IllegalArgumentException("unsupported HostedEnvironmentIdentity name: " + name);
    }
    try {
      HostedIdentityProperties.requireValidHostname("derived hostname", hostname);
    } catch (IllegalStateException exception) {
      throw new IllegalArgumentException(exception.getMessage(), exception);
    }
    String materialPrefix = runtimeNamespace;
    Map<String, String> publicationCertificates = new LinkedHashMap<>();
    Map<String, String> publicationSecrets = new LinkedHashMap<>();
    Map<String, String> publicationSourceSecrets = new LinkedHashMap<>();
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      String role = HostedIdentityContract.grpcPublicationRole(workload);
      String sourceName = materialPrefix + "-grpc-" + workload;
      publicationCertificates.put(role, sourceName);
      publicationSourceSecrets.put(role, sourceName);
      publicationSecrets.put(role, "firemud-grpc-" + workload);
    }
    return new EnvironmentIdentityPlan(
        name,
        properties.getControlNamespace(),
        identityNamespace,
        runtimeNamespace,
        hostname,
        materialPrefix + "-tls",
        materialPrefix + "-tls",
        materialPrefix + "-telnet-tls",
        materialPrefix + "-telnet-tls",
        materialPrefix + "-gateway-internal-ws",
        materialPrefix + "-gateway-internal-ws",
        "spring-cloud-gateway-mtls." + runtimeNamespace + ".svc.cluster.local",
        materialPrefix + "-tcp-proxy-bridge",
        materialPrefix + "-tcp-proxy-bridge",
        "spiffe://firemud/ns/" + runtimeNamespace + "/sa/tcp-proxy-service",
        "firemud-grpc-tls",
        "firemud-grpc-tls",
        properties.getIngressIssuer(),
        properties.getTelnetIssuer(),
        properties.getGrpcIssuer(),
        properties.getCaSecretName(),
        GRPC_CONSUMERS,
        publicationCertificates,
        publicationSecrets,
        publicationSourceSecrets);
  }

  public List<String> grpcConsumers() {
    return GRPC_CONSUMERS;
  }

  public String controlNamespace() {
    return properties.getControlNamespace();
  }
}
