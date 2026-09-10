package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class EnvironmentIdentityPlannerTest {
  private static final Pattern RUNTIME_DEPLOYMENT_RESOURCE_NAMES =
      Pattern.compile(
          "r\\.apiGroups\\s*==\\s*\\['apps']\\s*&&\\s*r\\.resources\\s*==\\s*\\['deployments']\\s*&&\\s*r\\.resourceNames\\s*==\\s*\\[([^\\]]+)]\\s*&&\\s*r\\.verbs\\s*==\\s*\\[([^\\]]+)]");
  private static final Pattern RUNTIME_SCOPE_MARKER =
      Pattern.compile("\\(object\\.metadata\\.name\\s*==\\s*'firemud-hosted-runtime-scope'");
  private static final Pattern CEL_STRING_LITERAL = Pattern.compile("'([^']+)'");
  private final EnvironmentIdentityPlanner planner =
      new EnvironmentIdentityPlanner(new HostedIdentityProperties());

  @Test
  void derivesPreviewTargetsFromOnlyTheClosedNameContract() {
    var plan = planner.plan("pr-42");

    assertEquals("firemud-system", plan.controlNamespace());
    assertEquals("pr-42-identity", plan.identityNamespace());
    assertEquals("pr-42", plan.runtimeNamespace());
    assertEquals("pr-42.preview.firedevops.net", plan.hostname());
    assertEquals("pr-42-tls", plan.ingressSecretName());
    assertEquals("pr-42-telnet-tls", plan.telnetSecretName());
    assertEquals("pr-42-gateway-internal-ws", plan.gatewayInternalWsSecretName());
    assertEquals(
        "spring-cloud-gateway-mtls.pr-42.svc.cluster.local", plan.gatewayInternalWsDnsName());
    assertEquals("pr-42-tcp-proxy-bridge", plan.tcpProxyBridgeSecretName());
    assertEquals("spiffe://firemud/ns/pr-42/sa/tcp-proxy-service", plan.tcpProxyBridgeUriSan());
    assertEquals("firemud-grpc-tls", plan.grpcSecretName());
    assertEquals(
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
            "world-management-service"),
        plan.grpcConsumers());
  }

  @Test
  void derivesDevDemoWithoutAcceptingUserSuppliedTopology() {
    var plan = planner.plan("dev-demo");

    assertEquals("dev-identity", plan.identityNamespace());
    assertEquals("dev", plan.runtimeNamespace());
    assertEquals("dev.preview.firedevops.net", plan.hostname());
    assertEquals("dev-tls", plan.ingressCertificateName());
    assertEquals("dev-tls", plan.ingressSecretName());
    assertEquals("dev-telnet-tls", plan.telnetCertificateName());
    assertEquals("dev-telnet-tls", plan.telnetSecretName());
    assertEquals("dev-gateway-internal-ws", plan.gatewayInternalWsCertificateName());
    assertEquals("dev-tcp-proxy-bridge", plan.tcpProxyBridgeCertificateName());
    assertEquals(
        "spring-cloud-gateway-mtls.dev.svc.cluster.local", plan.gatewayInternalWsDnsName());
    assertEquals("spiffe://firemud/ns/dev/sa/tcp-proxy-service", plan.tcpProxyBridgeUriSan());
  }

  @Test
  void rejectsAnOverlongDerivedPreviewHostname() {
    var properties = new HostedIdentityProperties();
    properties.setPreviewDomain(
        "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(56));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EnvironmentIdentityPlanner(properties).plan("pr-42"));
    assertEquals(
        "derived hostname must match the lowercase hostname contract and contain at most 253 characters",
        failure.getMessage());
    assertEquals(failure.getMessage(), failure.getCause().getMessage());
    assertInstanceOf(IllegalStateException.class, failure.getCause());
  }

  @Test
  void acceptsTheMaximumLengthDerivedPreviewHostname() {
    var properties = new HostedIdentityProperties();
    properties.setPreviewDomain(
        "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(55));

    var plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");

    assertEquals(253, plan.hostname().length());
    assertTrue(plan.hostname().endsWith("." + "a".repeat(55)));
  }

  @Test
  void acceptsTheMaximumLengthPreviewIdentityNamespace() {
    var plan = planner.plan("pr-" + "7".repeat(51));

    assertEquals(63, plan.identityNamespace().length());
  }

  @Test
  void rejectsPreviewNamesThatWouldExceedTheKubernetesNamespaceLimit() {
    String name = "pr-" + "7".repeat(52);

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> planner.plan(name));

    assertEquals("unsupported HostedEnvironmentIdentity name: " + name, failure.getMessage());
  }

  @Test
  void grpcConsumersExactlyMatchTheAdmissionDeploymentAllowlist() throws IOException {
    Path admissionPath = findRepositoryFile("k8s/hosted-identity-controller/admission.yaml");
    Map<?, ?> scopeRolePolicy;
    try (var reader = Files.newBufferedReader(admissionPath)) {
      scopeRolePolicy =
          java.util.stream.StreamSupport.stream(new Yaml().loadAll(reader).spliterator(), false)
              .map(Map.class::cast)
              .filter(document -> "ValidatingAdmissionPolicy".equals(document.get("kind")))
              .filter(
                  document ->
                      "firemud-hosted-identity-scope-roles"
                          .equals(((Map<?, ?>) document.get("metadata")).get("name")))
              .findFirst()
              .orElseThrow(() -> new AssertionError("scope-role admission policy must exist"));
    }
    Map<?, ?> spec = (Map<?, ?>) scopeRolePolicy.get("spec");
    List<?> validations = (List<?>) spec.get("validations");
    List<String> runtimeScopeExpressions =
        validations.stream()
            .map(Map.class::cast)
            .map(validation -> validation.get("expression"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(expression -> RUNTIME_SCOPE_MARKER.matcher(expression).find())
            .toList();
    assertEquals(
        1, runtimeScopeExpressions.size(), "exactly one runtime-scope admission branch must exist");
    String scopeRoleExpression = runtimeScopeExpressions.get(0);
    Matcher runtimeScope = RUNTIME_SCOPE_MARKER.matcher(scopeRoleExpression);
    assertTrue(runtimeScope.find(), "runtime-scope admission branch marker must exist");
    String runtimeScopePolicy = scopeRoleExpression.substring(runtimeScope.start());

    Matcher resourceNames = RUNTIME_DEPLOYMENT_RESOURCE_NAMES.matcher(runtimeScopePolicy);
    assertTrue(resourceNames.find(), "runtime deployment resourceNames rule must exist");
    List<String> admittedConsumers = celStringLiterals(resourceNames.group(1));
    List<String> admittedVerbs = celStringLiterals(resourceNames.group(2));

    assertEquals(planner.plan("pr-42").grpcConsumers(), admittedConsumers);
    assertEquals(planner.plan("dev-demo").grpcConsumers(), admittedConsumers);
    assertEquals(List.of("get", "update", "patch"), admittedVerbs);
    assertFalse(resourceNames.find(), "admission deployment matcher must have exactly one rule");
  }

  @Test
  void mapsEachConfiguredIssuerAndCaSecretToItsNamedPlanAccessor() {
    var properties = new HostedIdentityProperties();
    properties.setIngressIssuer("sentinel-ingress-issuer");
    properties.setTelnetIssuer("sentinel-telnet-issuer");
    properties.setGrpcIssuer("sentinel-grpc-issuer");
    properties.setCaSecretName("sentinel-grpc-ca-secret");

    var plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");

    assertEquals("sentinel-ingress-issuer", plan.ingressIssuer());
    assertEquals("sentinel-telnet-issuer", plan.telnetIssuer());
    assertEquals("sentinel-grpc-issuer", plan.grpcIssuer());
    assertEquals("sentinel-grpc-ca-secret", plan.caSecretName());
  }

  @Test
  void mapsEveryIdentityRoleToItsSecretAndCopiesOnlyGrpcConsumers() {
    var plan = planner.plan("pr-42");

    assertEquals(plan.ingressSecretName(), plan.secretName(HostedIdentityContract.INGRESS_ROLE));
    assertEquals(plan.telnetSecretName(), plan.secretName(HostedIdentityContract.TELNET_ROLE));
    assertEquals(
        plan.gatewayInternalWsSecretName(),
        plan.secretName(HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE));
    assertEquals(
        plan.tcpProxyBridgeSecretName(),
        plan.secretName(HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE));
    assertEquals(plan.grpcSecretName(), plan.secretName(HostedIdentityContract.GRPC_ROLE));
    assertThrows(IllegalArgumentException.class, () -> plan.secretName("unsupported"));

    List<String> consumers = new ArrayList<>(List.of("account-service"));
    var copy = plan.withGrpcConsumers(consumers);
    consumers.add("tcp-proxy-service");

    assertEquals(List.of("account-service"), copy.grpcConsumers());
    assertEquals(plan, copy.withGrpcConsumers(plan.grpcConsumers()));
    assertTrue(plan.grpcConsumers().size() > copy.grpcConsumers().size());
  }

  @Test
  void rejectsNamesOutsideTheFixedEnvironmentSet() {
    assertThrows(IllegalArgumentException.class, () -> planner.plan(null));
    assertThrows(IllegalArgumentException.class, () -> planner.plan(""));
    assertThrows(IllegalArgumentException.class, () -> planner.plan(" \t"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-0"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("production"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("preview-pr-42"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-42x"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-42-other"));
  }

  private static Path findRepositoryFile(String relativePath) {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve(relativePath);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
        break;
      }
      directory = directory.getParent();
    }
    throw new AssertionError("could not locate repository file " + relativePath);
  }

  private static List<String> celStringLiterals(String expression) {
    Matcher literal = CEL_STRING_LITERAL.matcher(expression);
    List<String> values = new ArrayList<>();
    while (literal.find()) {
      values.add(literal.group(1));
    }
    return values;
  }
}
