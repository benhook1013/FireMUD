package net.firedevops.firemud.hostedidentity.kubernetes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/**
 * Reads the current runtime identity tuple; a recreated namespace cannot reuse stale Ready status.
 */
@Component
public class RuntimeProfileService {
  private static final int PREVIEW_TELNET_PORT_ALLOCATION_WIDTH = 16;

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
    if (HostedIdentityContract.isDevDemo(plan.name())) {
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
        HostedIdentityContract.isDevDemo(plan.name())
            ? properties.getDevDemoTelnetPortAnnotation()
            : properties.getPreviewTelnetPortAnnotation();
    String portValue = annotations == null ? null : annotations.get(portAnnotation);
    if (portValue == null || portValue.isBlank()) {
      throw new IllegalStateException("runtime Namespace has no canonical Telnet port identity");
    }
    int port;
    try {
      port = Integer.parseInt(portValue);
    } catch (NumberFormatException exception) {
      throw invalidTelnetPortIdentity(portValue, exception);
    }
    if (!isValidTelnetPort(plan, port)) {
      throw invalidTelnetPortIdentity(
          portValue,
          new IllegalArgumentException("parsed Telnet port is outside the configured allocation"));
    }
    return new RuntimeProfile(
        namespace.getMetadata().getUid(), requestedHead, deployedHead, port, true);
  }

  /**
   * Compares the complete runtime identity tuple, including presence and the allocated port.
   *
   * <p>The comparison deliberately does not reduce the tuple to the namespace UID or head SHA:
   * those values alone cannot detect a stale port binding or a namespace disappearing between
   * reconciliation boundaries.
   */
  public static boolean exactlyMatches(RuntimeProfile expected, RuntimeProfile actual) {
    return Objects.equals(expected, actual);
  }

  /** Returns the identity fields that changed between two runtime observations. */
  public static String changedFields(RuntimeProfile expected, RuntimeProfile actual) {
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(
        value(expected, RuntimeProfile::runtimeNamespaceUid),
        value(actual, RuntimeProfile::runtimeNamespaceUid))) {
      changed.add("runtime Namespace UID");
    }
    if (!Objects.equals(
        value(expected, RuntimeProfile::requestedHeadSha),
        value(actual, RuntimeProfile::requestedHeadSha))) {
      changed.add("requested head");
    }
    if (!Objects.equals(
        value(expected, RuntimeProfile::deployedHeadSha),
        value(actual, RuntimeProfile::deployedHeadSha))) {
      changed.add("deployed head");
    }
    if (!Objects.equals(
        value(expected, RuntimeProfile::telnetPort), value(actual, RuntimeProfile::telnetPort))) {
      changed.add("Telnet port");
    }
    if (!Objects.equals(
        value(expected, RuntimeProfile::present), value(actual, RuntimeProfile::present))) {
      changed.add("presence");
    }
    return changed.isEmpty() ? "runtime identity tuple" : String.join(", ", changed);
  }

  private static <T> T value(
      RuntimeProfile profile, java.util.function.Function<RuntimeProfile, T> field) {
    return profile == null ? null : field.apply(profile);
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

  private static IllegalStateException invalidTelnetPortIdentity(
      String portValue, RuntimeException cause) {
    return new IllegalStateException(
        "runtime Namespace has an invalid Telnet port identity: " + portValue, cause);
  }

  boolean isValidTelnetPort(EnvironmentIdentityPlan plan, int port) {
    if (port < 1 || port > 65535) {
      return false;
    }
    if (HostedIdentityContract.isDevDemo(plan.name())) {
      return port == properties.getDevDemoTelnetPort();
    }
    int base = properties.getPreviewTelnetPortBase();
    return base >= 1
        && base <= 65536 - PREVIEW_TELNET_PORT_ALLOCATION_WIDTH
        && port >= base
        && port < base + PREVIEW_TELNET_PORT_ALLOCATION_WIDTH;
  }

  static void validateRuntimeLabels(EnvironmentIdentityPlan plan, Map<String, String> labels) {
    if (labels == null) {
      throw new IllegalStateException("runtime Namespace has no lifecycle labels");
    }
    if (HostedIdentityContract.isDevDemo(plan.name())) {
      requireLabel(labels, "firemud.dev/dev-demo", "true");
      requireLabel(
          labels,
          "firemud.dev/environment-class",
          HostedIdentityContract.environmentClass(plan.name()));
      return;
    }
    if (!plan.name().startsWith("pr-")) {
      throw new IllegalStateException("runtime Namespace has an invalid identity plan name");
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
