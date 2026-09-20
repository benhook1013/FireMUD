package net.firedevops.firemud.hostedidentity.kubernetes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
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
  private static final String TCP_PROXY_SERVICE_NAME = "tcp-proxy-service";
  private static final int TCP_PROXY_TELNET_PORT = 2323;

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
    String exposureMode = exposureMode(plan, namespace.getMetadata().getLabels());
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
    int port;
    if (HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE.equals(exposureMode)) {
      if (annotations != null && annotations.containsKey(portAnnotation)) {
        throw new IllegalStateException(
            "private runtime Namespace cannot carry a canonical Telnet port identity");
      }
      port = 0;
    } else {
      String portValue = annotations == null ? null : annotations.get(portAnnotation);
      if (portValue == null || portValue.isBlank()) {
        throw new IllegalStateException("runtime Namespace has no canonical Telnet port identity");
      }
      try {
        port = Integer.parseInt(portValue);
      } catch (NumberFormatException exception) {
        throw invalidTelnetPortIdentity(portValue, exception);
      }
      if (!isValidTelnetPort(plan, port)) {
        throw invalidTelnetPortIdentity(
            portValue,
            new IllegalArgumentException(
                "parsed Telnet port is outside the configured allocation"));
      }
    }
    // Runtime Namespace preparation and identity projection may precede Helm's Service apply.
    // The reconciler applies validateTcpProxyService only after deployed-head evidence exists.
    return new RuntimeProfile(
        namespace.getMetadata().getUid(), requestedHead, deployedHead, exposureMode, port, true);
  }

  public void validateTcpProxyService(
      KubernetesClient client, EnvironmentIdentityPlan plan, RuntimeProfile runtimeProfile) {
    if (runtimeProfile == null || !runtimeProfile.present()) {
      throw new IllegalStateException("cannot validate a non-present runtime profile");
    }
    validateTcpProxyService(
        client, plan, runtimeProfile.exposureMode(), runtimeProfile.telnetPort());
  }

  private static void validateTcpProxyService(
      KubernetesClient client, EnvironmentIdentityPlan plan, String exposureMode, int telnetPort) {
    Service service;
    try {
      service =
          client
              .services()
              .inNamespace(plan.runtimeNamespace())
              .withName(TCP_PROXY_SERVICE_NAME)
              .get();
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "runtime " + TCP_PROXY_SERVICE_NAME + " Service could not be read", exception);
    }
    if (service == null || service.getSpec() == null) {
      throw new IllegalStateException(
          "runtime " + TCP_PROXY_SERVICE_NAME + " Service is absent or malformed");
    }

    String expectedType =
        HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE.equals(exposureMode)
            ? "ClusterIP"
            : "NodePort";
    if (!expectedType.equals(service.getSpec().getType())) {
      throw new IllegalStateException(
          "runtime "
              + TCP_PROXY_SERVICE_NAME
              + " Service type does not match exposure mode: expected "
              + expectedType);
    }
    if (HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE.equals(exposureMode)
        && service.getSpec().getExternalIPs() != null
        && !service.getSpec().getExternalIPs().isEmpty()) {
      throw new IllegalStateException(
          "private runtime " + TCP_PROXY_SERVICE_NAME + " Service cannot carry external IPs");
    }
    List<ServicePort> ports = service.getSpec().getPorts();
    if (ports == null || ports.isEmpty()) {
      throw new IllegalStateException(
          "runtime " + TCP_PROXY_SERVICE_NAME + " Service has no ports");
    }

    int telnetPortMatches = 0;
    int nodePortCount = 0;
    for (ServicePort servicePort : ports) {
      if (servicePort == null || servicePort.getPort() == null) {
        throw new IllegalStateException(
            "runtime " + TCP_PROXY_SERVICE_NAME + " Service has a malformed port");
      }
      Integer nodePort = servicePort.getNodePort();
      if (nodePort != null) {
        nodePortCount++;
      }
      if (!Integer.valueOf(TCP_PROXY_TELNET_PORT).equals(servicePort.getPort())) {
        continue;
      }
      telnetPortMatches++;
      if (telnetPortMatches > 1) {
        throw new IllegalStateException(
            "runtime " + TCP_PROXY_SERVICE_NAME + " Service has duplicate Telnet ports");
      }
      if (HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE.equals(exposureMode)) {
        if (nodePort != null) {
          throw new IllegalStateException(
              "private runtime " + TCP_PROXY_SERVICE_NAME + " Service cannot carry a NodePort");
        }
      } else if (!Integer.valueOf(telnetPort).equals(nodePort)) {
        throw new IllegalStateException(
            "public runtime "
                + TCP_PROXY_SERVICE_NAME
                + " Service Telnet NodePort does not match the trusted allocation");
      }
    }
    if (telnetPortMatches != 1) {
      throw new IllegalStateException(
          "runtime " + TCP_PROXY_SERVICE_NAME + " Service must expose exactly one Telnet port");
    }
    if (HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE.equals(exposureMode)) {
      if (nodePortCount != 0) {
        throw new IllegalStateException(
            "private runtime " + TCP_PROXY_SERVICE_NAME + " Service cannot carry a NodePort");
      }
    } else if (nodePortCount != 1) {
      throw new IllegalStateException(
          "public runtime "
              + TCP_PROXY_SERVICE_NAME
              + " Service must carry exactly one trusted NodePort");
    }
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
        value(expected, RuntimeProfile::exposureMode),
        value(actual, RuntimeProfile::exposureMode))) {
      changed.add("exposure mode");
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

  public boolean isValidTelnetPort(EnvironmentIdentityPlan plan, int port) {
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
      String exposureMode = labels.get(HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL);
      if (exposureMode != null
          && !HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE.equals(exposureMode)) {
        throw new IllegalStateException(
            "runtime Namespace has an invalid "
                + HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL
                + " label");
      }
      return;
    }
    if (!plan.name().startsWith("pr-")) {
      throw new IllegalStateException("runtime Namespace has an invalid identity plan name");
    }
    requireLabel(labels, "firemud.dev/preview", "true");
    requireLabel(labels, "firemud.dev/pr-number", plan.name().substring("pr-".length()));
    requireLabel(
        labels,
        HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL,
        HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE,
        HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE);
  }

  private static String exposureMode(EnvironmentIdentityPlan plan, Map<String, String> labels) {
    if (HostedIdentityContract.isDevDemo(plan.name())) {
      return HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE;
    }
    String mode = labels.get(HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL);
    if (!isValidExposureMode(mode)) {
      throw new IllegalStateException("runtime Namespace has an invalid preview exposure mode");
    }
    return mode;
  }

  public static boolean isValidExposureMode(String exposureMode) {
    return HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE.equals(exposureMode)
        || HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE.equals(exposureMode);
  }

  private static void requireLabel(Map<String, String> labels, String key, String... expected) {
    for (String value : expected) {
      if (value.equals(labels.get(key))) {
        return;
      }
    }
    throw new IllegalStateException("runtime Namespace has an invalid " + key + " label");
  }

  public record RuntimeProfile(
      String runtimeNamespaceUid,
      String requestedHeadSha,
      String deployedHeadSha,
      String exposureMode,
      int telnetPort,
      boolean present) {
    public boolean deployedHeadMatchesRequest() {
      return present
          && requestedHeadSha != null
          && Objects.equals(requestedHeadSha, deployedHeadSha);
    }

    public static RuntimeProfile absent() {
      return new RuntimeProfile(null, null, null, null, 0, false);
    }
  }
}
