package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertEquals("pr-42-tls", ingressSpec.get("secretName"));
    assertEquals("pr-42-telnet-tls", telnetSpec.get("secretName"));
    assertEquals("letsencrypt-prod", ((Map<?, ?>) ingressSpec.get("issuerRef")).get("name"));
    assertEquals(
        "pr-42.preview.firedevops.net", ((java.util.List<?>) ingressSpec.get("dnsNames")).get(0));
    assertEquals(
        "pr-42.preview.firedevops.net", ((java.util.List<?>) telnetSpec.get("dnsNames")).get(0));
    assertThrows(
        NoSuchMethodException.class,
        () -> CertificateResourceFactory.class.getMethod("grpc", plan.getClass()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec(
      io.fabric8.kubernetes.api.model.GenericKubernetesResource resource) {
    return (Map<String, Object>) resource.getAdditionalProperties().get("spec");
  }
}
