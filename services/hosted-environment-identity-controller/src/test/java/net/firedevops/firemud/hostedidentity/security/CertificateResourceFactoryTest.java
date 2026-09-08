package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateResourceFactory;
import org.junit.jupiter.api.Test;

class CertificateResourceFactoryTest {
  @Test
  void ingressAndTelnetUseSeparateCertificatesAndFixedIssuer() {
    var properties = new HostedIdentityProperties();
    properties.setGrpcRenewBefore(Duration.ofHours(5));
    var plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    var factory = new CertificateResourceFactory();

    var ingress = factory.ingress(plan);
    var telnet = factory.telnet(plan);
    var ingressSpec = spec(ingress);
    var telnetSpec = spec(telnet);
    var gatewaySpec = spec(factory.gatewayInternalWs(plan, properties.getGrpcRenewBefore()));
    var bridgeSpec = spec(factory.tcpProxyBridge(plan, properties.getGrpcRenewBefore()));
    assertEquals("pr-42-tls", ingress.getMetadata().getName());
    assertEquals("pr-42-telnet-tls", telnet.getMetadata().getName());
    assertEquals("pr-42-tls", ingressSpec.get("secretName"));
    assertEquals("pr-42-telnet-tls", telnetSpec.get("secretName"));
    Map<?, ?> secretTemplate = (Map<?, ?>) ingressSpec.get("secretTemplate");
    assertFalse(secretTemplate.containsKey("metadata"));
    assertEquals(
        HostedIdentityContract.managedLabels(plan.name(), HostedIdentityContract.INGRESS_ROLE),
        secretTemplate.get("labels"));
    assertEquals(
        Map.of(
            HostedIdentityContract.PROVENANCE_ANNOTATION,
            "cert-manager",
            HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
            "source-materialized"),
        secretTemplate.get("annotations"));
    assertFalse(ingressSpec.containsKey("duration"));
    assertFalse(ingressSpec.containsKey("renewBefore"));
    assertFalse(telnetSpec.containsKey("duration"));
    assertFalse(telnetSpec.containsKey("renewBefore"));
    assertEquals("letsencrypt-prod", ((Map<?, ?>) ingressSpec.get("issuerRef")).get("name"));
    assertEquals("letsencrypt-prod", ((Map<?, ?>) telnetSpec.get("issuerRef")).get("name"));
    assertEquals(
        "pr-42.preview.firedevops.net", ((java.util.List<?>) ingressSpec.get("dnsNames")).get(0));
    assertEquals(
        "pr-42.preview.firedevops.net", ((java.util.List<?>) telnetSpec.get("dnsNames")).get(0));
    assertEquals("pr-42-gateway-internal-ws", gatewaySpec.get("secretName"));
    assertEquals("firemud-ca-issuer", ((Map<?, ?>) gatewaySpec.get("issuerRef")).get("name"));
    assertEquals("720h", gatewaySpec.get("duration"));
    assertEquals("5h", gatewaySpec.get("renewBefore"));
    assertEquals(
        java.util.List.of("spring-cloud-gateway-mtls.pr-42.svc.cluster.local"),
        gatewaySpec.get("dnsNames"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "server auth"),
        gatewaySpec.get("usages"));
    assertEquals("pr-42-tcp-proxy-bridge", bridgeSpec.get("secretName"));
    assertEquals("firemud-ca-issuer", ((Map<?, ?>) bridgeSpec.get("issuerRef")).get("name"));
    assertEquals("720h", bridgeSpec.get("duration"));
    assertEquals("5h", bridgeSpec.get("renewBefore"));
    assertFalse(bridgeSpec.containsKey("dnsNames"));
    assertEquals(
        java.util.List.of("spiffe://firemud/ns/pr-42/sa/tcp-proxy-service"),
        bridgeSpec.get("uris"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "client auth"),
        bridgeSpec.get("usages"));
    assertTrue(
        java.util.Arrays.stream(CertificateResourceFactory.class.getDeclaredMethods())
            .noneMatch(method -> method.getName().equals("grpc")),
        "CertificateResourceFactory must not issue gRPC material");
  }

  @Test
  void internalCertificatesDefensivelyRejectInvalidRenewalWindows() {
    var plan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var factory = new CertificateResourceFactory();

    for (Duration invalidRenewBefore :
        java.util.List.of(Duration.ofMinutes(5).minusNanos(1), Duration.ofDays(30))) {
      assertInvalidRenewalWindow(() -> factory.gatewayInternalWs(plan, invalidRenewBefore));
      assertInvalidRenewalWindow(() -> factory.tcpProxyBridge(plan, invalidRenewBefore));
    }
  }

  private static void assertInvalidRenewalWindow(
      org.junit.jupiter.api.function.Executable factoryCall) {
    IllegalStateException failure = assertThrows(IllegalStateException.class, factoryCall);
    assertEquals(
        "gRPC renewal window must be at least 5 minutes and shorter than 30 days",
        failure.getMessage());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec(
      io.fabric8.kubernetes.api.model.GenericKubernetesResource resource) {
    return (Map<String, Object>) resource.getAdditionalProperties().get("spec");
  }
}
