package net.firedevops.firemud.hostedidentity.security;

import java.util.List;
import java.util.regex.Pattern;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentIdentityPlanner {
  private static final Pattern PREVIEW_NAME = Pattern.compile("pr-[1-9][0-9]*");
  private static final String DEV_DEMO_NAME = "dev-demo";
  private static final List<String> GRPC_CONSUMERS =
      List.of(
          "account-service",
          "automation-scripting-service",
          "entity-management-service",
          "game-design-service",
          "game-logic-service",
          "game-session-service",
          "logging-admin-service",
          "social-groups-service",
          "spring-cloud-gateway",
          "tcp-proxy-service",
          "world-management-service");

  private final HostedIdentityProperties properties;

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
    String materialPrefix = DEV_DEMO_NAME.equals(name) ? runtimeNamespace : name;
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
        "firemud-grpc-tls",
        "firemud-grpc-tls",
        properties.getIngressIssuer(),
        properties.getTelnetIssuer(),
        properties.getGrpcIssuer(),
        properties.getCaSecretName(),
        GRPC_CONSUMERS);
  }

  public List<String> grpcConsumers() {
    return GRPC_CONSUMERS;
  }

  public String controlNamespace() {
    return properties.getControlNamespace();
  }
}
