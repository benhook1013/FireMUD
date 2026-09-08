package net.firedevops.firemud.hostedidentity.kubernetes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/**
 * Reads the current runtime identity tuple; a recreated namespace cannot reuse stale Ready status.
 */
@Component
public class RuntimeProfileService {
  private final HostedIdentityProperties properties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected configuration is application-scoped and is never exposed.")
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
    Map<String, String> annotations = namespace.getMetadata().getAnnotations();
    String requestedHead;
    String deployedHead;
    if (plan.name().equals("dev-demo")) {
      requestedHead =
          requiredCanonicalHead(
              annotations,
              properties.getDevDemoRequestedHeadAnnotation(),
              "requested head identity");
      deployedHead =
          optionalCanonicalHead(
              annotations, properties.getDevDemoHeadAnnotation(), "deployed head identity");
    } else {
      requestedHead =
          requiredCanonicalHead(
              annotations,
              properties.getPreviewRequestedHeadAnnotation(),
              "requested head identity");
      deployedHead =
          optionalCanonicalHead(
              annotations, properties.getPreviewDeployedHeadAnnotation(), "deployed head identity");
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
      return new RuntimeProfile(
          namespace.getMetadata().getUid(), requestedHead, deployedHead, port, true);
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("runtime Namespace has an invalid Telnet port identity");
    }
  }

  private static String requiredCanonicalHead(
      Map<String, String> annotations, String annotation, String identity) {
    String head = annotations == null ? null : annotations.get(annotation);
    if (head == null || !head.matches("[0-9a-fA-F]{40}")) {
      throw new IllegalStateException("runtime Namespace has no canonical " + identity);
    }
    return head.toLowerCase(Locale.ROOT);
  }

  private static String optionalCanonicalHead(
      Map<String, String> annotations, String annotation, String identity) {
    String head = annotations == null ? null : annotations.get(annotation);
    if (head != null && !head.matches("[0-9a-fA-F]{40}")) {
      throw new IllegalStateException("runtime Namespace has an invalid canonical " + identity);
    }
    return head == null ? null : head.toLowerCase(Locale.ROOT);
  }

  boolean isValidTelnetPort(EnvironmentIdentityPlan plan, int port) {
    if (port < 1 || port > 65535) {
      return false;
    }
    if (plan.name().equals("dev-demo")) {
      return port == properties.getDevDemoTelnetPort();
    }
    int base = properties.getPreviewTelnetPortBase();
    return base >= 1 && base <= 65520 && port >= base && port <= base + 15;
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
      String runtimeNamespaceUid,
      String requestedHeadSha,
      String deployedHeadSha,
      int telnetPort,
      boolean present) {
    public boolean deployedHeadMatchesRequest() {
      return present
          && requestedHeadSha != null
          && Objects.equals(requestedHeadSha, deployedHeadSha);
    }

    public static RuntimeProfile absent() {
      return new RuntimeProfile(null, null, null, 0, false);
    }
  }
}
