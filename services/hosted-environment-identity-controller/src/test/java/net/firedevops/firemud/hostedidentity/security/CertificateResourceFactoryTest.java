package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateResourceFactory;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.junit.jupiter.api.Test;

class CertificateResourceFactoryTest {
  @Test
  void ingressCertificateUsesPublicServerMaterialContract() {
    var plan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var factory = new CertificateResourceFactory();

    var certificate = factory.ingress(plan);
    var certificateSpec = spec(certificate);
    assertEquals("pr-42-tls", certificate.getMetadata().getName());
    assertEquals("pr-42-tls", certificateSpec.get("secretName"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "server auth"),
        certificateSpec.get("usages"));
    assertEquals(
        java.util.List.of("pr-42.preview.firedevops.net"), certificateSpec.get("dnsNames"));
    assertEquals("letsencrypt-prod", issuerName(certificateSpec));
    assertCertificateDefaults(certificateSpec);
    assertFalse(certificateSpec.containsKey("duration"));
    assertFalse(certificateSpec.containsKey("renewBefore"));
    assertSecretTemplate(certificateSpec, plan, HostedIdentityContract.INGRESS_ROLE);
  }

  @Test
  void telnetCertificateUsesPublicServerMaterialContract() {
    var plan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var factory = new CertificateResourceFactory();

    var certificate = factory.telnet(plan);
    var certificateSpec = spec(certificate);
    assertEquals("pr-42-telnet-tls", certificate.getMetadata().getName());
    assertEquals("pr-42-telnet-tls", certificateSpec.get("secretName"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "server auth"),
        certificateSpec.get("usages"));
    assertEquals(
        java.util.List.of("pr-42.preview.firedevops.net"), certificateSpec.get("dnsNames"));
    assertEquals("letsencrypt-prod", issuerName(certificateSpec));
    assertCertificateDefaults(certificateSpec);
    assertSecretTemplate(certificateSpec, plan, HostedIdentityContract.TELNET_ROLE);
    assertFalse(certificateSpec.containsKey("duration"));
    assertFalse(certificateSpec.containsKey("renewBefore"));
  }

  @Test
  void gatewayInternalWsCertificateUsesInternalServerMaterialContract() {
    var properties = propertiesWithRenewBefore();
    var plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    var certificate =
        new CertificateResourceFactory().gatewayInternalWs(plan, properties.getGrpcRenewBefore());
    var certificateSpec = spec(certificate);

    assertEquals("pr-42-gateway-internal-ws", certificate.getMetadata().getName());
    assertEquals("pr-42-gateway-internal-ws", certificateSpec.get("secretName"));
    assertEquals("firemud-ca-issuer", issuerName(certificateSpec));
    assertEquals("720h", certificateSpec.get("duration"));
    assertEquals("5h", certificateSpec.get("renewBefore"));
    assertEquals(
        java.util.List.of("spring-cloud-gateway-mtls.pr-42.svc.cluster.local"),
        certificateSpec.get("dnsNames"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "server auth"),
        certificateSpec.get("usages"));
    assertCertificateDefaults(certificateSpec);
    assertSecretTemplate(certificateSpec, plan, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE);
  }

  @Test
  void tcpProxyBridgeCertificateUsesInternalClientMaterialContract() {
    var properties = propertiesWithRenewBefore();
    var plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    var certificate =
        new CertificateResourceFactory().tcpProxyBridge(plan, properties.getGrpcRenewBefore());
    var certificateSpec = spec(certificate);

    assertEquals("pr-42-tcp-proxy-bridge", certificate.getMetadata().getName());
    assertEquals("pr-42-tcp-proxy-bridge", certificateSpec.get("secretName"));
    assertEquals("firemud-ca-issuer", issuerName(certificateSpec));
    assertEquals("720h", certificateSpec.get("duration"));
    assertEquals("5h", certificateSpec.get("renewBefore"));
    assertFalse(certificateSpec.containsKey("dnsNames"));
    assertEquals(
        java.util.List.of("spiffe://firemud/ns/pr-42/sa/tcp-proxy-service"),
        certificateSpec.get("uris"));
    assertEquals(
        java.util.List.of("digital signature", "key encipherment", "client auth"),
        certificateSpec.get("usages"));
    assertCertificateDefaults(certificateSpec);
    assertSecretTemplate(certificateSpec, plan, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE);
  }

  @Test
  void certificateFactoryHasNoGrpcCertificateFactoryMethod() {
    assertTrue(
        java.util.Arrays.stream(CertificateResourceFactory.class.getDeclaredMethods())
            .noneMatch(method -> method.getName().equals("grpc")));
  }

  @Test
  void internalCertificatesDefensivelyRejectInvalidRenewalWindows() {
    var plan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var factory = new CertificateResourceFactory();

    assertInvalidRenewalWindow(() -> factory.gatewayInternalWs(plan, null));
    assertInvalidRenewalWindow(() -> factory.tcpProxyBridge(plan, null));

    for (Duration invalidRenewBefore :
        java.util.List.of(
            Duration.ofMinutes(5).minusNanos(1),
            HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION
                .minus(HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK)
                .plusNanos(1),
            Duration.ofDays(30))) {
      assertInvalidRenewalWindow(() -> factory.gatewayInternalWs(plan, invalidRenewBefore));
      assertInvalidRenewalWindow(() -> factory.tcpProxyBridge(plan, invalidRenewBefore));
    }
  }

  @Test
  void internalCertificatesAcceptBothExactValidRenewalBoundaries() {
    var plan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var factory = new CertificateResourceFactory();

    for (Duration validRenewBefore :
        java.util.List.of(
            Duration.ofMinutes(5),
            HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION.minus(
                HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK))) {
      assertDoesNotThrow(() -> factory.gatewayInternalWs(plan, validRenewBefore));
      assertDoesNotThrow(() -> factory.tcpProxyBridge(plan, validRenewBefore));
    }
  }

  private static HostedIdentityProperties propertiesWithRenewBefore() {
    var properties = new HostedIdentityProperties();
    properties.setGrpcRenewBefore(Duration.ofHours(5));
    return properties;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec(
      io.fabric8.kubernetes.api.model.GenericKubernetesResource resource) {
    return (Map<String, Object>) resource.getAdditionalProperties().get("spec");
  }

  private static String issuerName(Map<?, ?> certificateSpec) {
    return (String) ((Map<?, ?>) certificateSpec.get("issuerRef")).get("name");
  }

  private static void assertCertificateDefaults(Map<?, ?> certificateSpec) {
    Map<?, ?> issuerRef = (Map<?, ?>) certificateSpec.get("issuerRef");
    Map<?, ?> privateKey = (Map<?, ?>) certificateSpec.get("privateKey");
    assertEquals("ClusterIssuer", issuerRef.get("kind"));
    assertEquals("cert-manager.io", issuerRef.get("group"));
    assertEquals("RSA", privateKey.get("algorithm"));
    assertEquals(2048, privateKey.get("size"));
    assertEquals("PKCS8", privateKey.get("encoding"));
    assertEquals("Always", privateKey.get("rotationPolicy"));
    assertEquals(true, certificateSpec.get("encodeUsagesInRequest"));
  }

  private static void assertSecretTemplate(
      Map<?, ?> certificateSpec, EnvironmentIdentityPlan plan, String role) {
    Map<?, ?> secretTemplate = (Map<?, ?>) certificateSpec.get("secretTemplate");
    assertFalse(secretTemplate.containsKey("metadata"));
    assertEquals(
        HostedIdentityContract.managedLabels(plan.name(), role), secretTemplate.get("labels"));
    assertEquals(
        Map.of(
            HostedIdentityContract.PROVENANCE_ANNOTATION,
            "cert-manager",
            HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
            "source-materialized"),
        secretTemplate.get("annotations"));
  }

  private static void assertInvalidRenewalWindow(
      org.junit.jupiter.api.function.Executable factoryCall) {
    IllegalStateException failure = assertThrows(IllegalStateException.class, factoryCall);
    assertEquals(
        "gRPC renewal window must be at least 5 minutes and leave at least 5 minutes before the 30-day certificate expiry",
        failure.getMessage());
  }
}
