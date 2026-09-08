package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateResourceFactory;
import org.junit.jupiter.api.Test;

class CertificateResourceFactoryTest {
  @Test
  void ingressAndTelnetUseSeparateCertificatesAndFixedIssuer() {
    var plan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var factory = new CertificateResourceFactory();

    var ingressSpec = spec(factory.ingress(plan));
    var telnetSpec = spec(factory.telnet(plan));
    var gatewaySpec = spec(factory.gatewayInternalWs(plan));
    var bridgeSpec = spec(factory.tcpProxyBridge(plan));
    assertEquals("pr-42-tls", ingressSpec.get("secretName"));
    assertEquals("pr-42-telnet-tls", telnetSpec.get("secretName"));
    assertEquals("letsencrypt-prod", ((Map<?, ?>) ingressSpec.get("issuerRef")).get("name"));
    assertEquals("letsencrypt-prod", ((Map<?, ?>) telnetSpec.get("issuerRef")).get("name"));
    assertEquals(
        "pr-42.preview.firedevops.net", ((java.util.List<?>) ingressSpec.get("dnsNames")).get(0));
    assertEquals(
        "pr-42.preview.firedevops.net", ((java.util.List<?>) telnetSpec.get("dnsNames")).get(0));
    assertEquals("pr-42-gateway-internal-ws", gatewaySpec.get("secretName"));
    assertEquals("firemud-ca-issuer", ((Map<?, ?>) gatewaySpec.get("issuerRef")).get("name"));
    assertEquals(
        java.util.List.of("spring-cloud-gateway-mtls.pr-42.svc.cluster.local"),
        gatewaySpec.get("dnsNames"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "server auth"),
        gatewaySpec.get("usages"));
    assertEquals("pr-42-tcp-proxy-bridge", bridgeSpec.get("secretName"));
    assertEquals("firemud-ca-issuer", ((Map<?, ?>) bridgeSpec.get("issuerRef")).get("name"));
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec(
      io.fabric8.kubernetes.api.model.GenericKubernetesResource resource) {
    return (Map<String, Object>) resource.getAdditionalProperties().get("spec");
  }
}
