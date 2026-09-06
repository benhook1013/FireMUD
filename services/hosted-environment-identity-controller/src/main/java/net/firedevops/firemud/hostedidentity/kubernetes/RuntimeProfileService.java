package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/**
 * Reads the current runtime identity tuple; a recreated namespace cannot reuse stale Ready status.
 */
@Component
public class RuntimeProfileService {
  private final HostedIdentityProperties properties;

  public RuntimeProfileService(HostedIdentityProperties properties) {
    this.properties = properties;
  }

  public RuntimeProfile read(KubernetesClient client, EnvironmentIdentityPlan plan) {
    Namespace namespace = client.namespaces().withName(plan.runtimeNamespace()).get();
    if (namespace == null) {
      return RuntimeProfile.absent();
    }
    if (namespace.getMetadata() == null
        || namespace.getMetadata().getUid() == null
        || namespace.getMetadata().getUid().isBlank()) {
      throw new IllegalStateException("runtime Namespace has no stable UID");
    }
    validateRuntimeLabels(plan, namespace.getMetadata().getLabels());
    String headAnnotation =
        plan.name().equals("dev-demo")
            ? properties.getDevDemoHeadAnnotation()
            : properties.getPreviewHeadAnnotation();
    Map<String, String> annotations = namespace.getMetadata().getAnnotations();
    String head = annotations == null ? null : annotations.get(headAnnotation);
    if (head == null || !head.matches("[0-9a-fA-F]{40}")) {
      throw new IllegalStateException("runtime Namespace has no canonical deployed head identity");
    }
    String portAnnotation =
        plan.name().equals("dev-demo")
            ? properties.getDevDemoTelnetPortAnnotation()
            : properties.getPreviewTelnetPortAnnotation();
    String portValue = annotations == null ? null : annotations.get(portAnnotation);
    if (portValue == null || portValue.isBlank()) {
      throw new IllegalStateException("runtime Namespace has no canonical Telnet port identity");
    }
    try {
      int port = Integer.parseInt(portValue);
      if (!isValidTelnetPort(plan, port)) {
        throw new NumberFormatException("out of range");
      }
      return new RuntimeProfile(namespace.getMetadata().getUid(), head, port, true);
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("runtime Namespace has an invalid Telnet port identity");
    }
  }

  static boolean isValidTelnetPort(EnvironmentIdentityPlan plan, int port) {
    return plan.name().equals("dev-demo") ? port == 32016 : port >= 32000 && port <= 32015;
  }

  static void validateRuntimeLabels(EnvironmentIdentityPlan plan, Map<String, String> labels) {
    if (labels == null) {
      throw new IllegalStateException("runtime Namespace has no lifecycle labels");
    }
    if (plan.name().equals("dev-demo")) {
      requireLabel(labels, "firemud.dev/dev-demo", "true");
      requireLabel(labels, "firemud.dev/environment-class", "dev-demo-cluster");
      return;
    }
    requireLabel(labels, "firemud.dev/preview", "true");
    requireLabel(labels, "firemud.dev/pr-number", plan.name().substring("pr-".length()));
  }

  private static void requireLabel(Map<String, String> labels, String key, String expected) {
    if (!expected.equals(labels.get(key))) {
      throw new IllegalStateException("runtime Namespace has an invalid " + key + " label");
    }
  }

  public record RuntimeProfile(
      String runtimeNamespaceUid, String deployedHeadSha, int telnetPort, boolean present) {
    public static RuntimeProfile absent() {
      return new RuntimeProfile(null, null, 0, false);
    }
  }
}
