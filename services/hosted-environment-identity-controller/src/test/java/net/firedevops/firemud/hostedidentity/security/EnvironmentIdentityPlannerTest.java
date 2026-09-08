package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import org.junit.jupiter.api.Test;

class EnvironmentIdentityPlannerTest {
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
  void rejectsNamesOutsideTheFixedEnvironmentSet() {
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-0"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("production"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-42-other"));
  }
}
