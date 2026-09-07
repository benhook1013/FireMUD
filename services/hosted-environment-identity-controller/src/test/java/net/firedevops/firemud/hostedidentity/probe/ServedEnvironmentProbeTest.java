package net.firedevops.firemud.hostedidentity.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.api.model.Secret;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.GrpcTransportBundleGenerator;
import org.junit.jupiter.api.Test;

class ServedEnvironmentProbeTest {
  @Test
  void configuredPortsOwnTheProbeDerivationBoundary() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setPreviewTelnetPortBase(41000);
    properties.setDevDemoTelnetPort(42000);
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);

    assertEquals(42000, probe.telnetPort("dev-demo"));
    assertEquals(41001, probe.telnetPort("pr-1"));
    assertEquals(41015, probe.telnetPort("pr-15"));
    assertThrows(IllegalArgumentException.class, () -> probe.telnetPort("pr-16"));
  }

  @Test
  void invalidConfiguredPortRangesFailClosed() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setPreviewTelnetPortBase(65521);
    properties.setDevDemoTelnetPort(0);
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);

    assertThrows(IllegalArgumentException.class, () -> probe.telnetPort("dev-demo"));
    assertThrows(IllegalArgumentException.class, () -> probe.telnetPort("pr-1"));
  }

  @Test
  void readinessProbeRequiresInternalGrpcAcceptanceAfterPublicEndpoints() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);
    ServedEnvironmentProbe.EndpointProbe ready =
        (hostname, port) -> new ServedEnvironmentProbe.ProbeResult(true, "ready");
    ServedEnvironmentProbe.EndpointProbe rejected =
        (hostname, port) -> new ServedEnvironmentProbe.ProbeResult(false, "rejected");

    assertEquals("grpc-rejected", probe.probe(plan, 32001, ready, ready, rejected).reason());
    assertEquals(
        "served-and-grpc-accepted", probe.probe(plan, 32001, ready, ready, ready).reason());
  }

  @Test
  void internalGrpcProbeCompletesMutualTlsWithFixedCaHostnameAndLeafPin() throws Exception {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    Secret material = generatedMaterial(plan);
    String trustAnchor = fingerprint(material.getData().get("ca.crt"));
    String leaf = fingerprint(material.getData().get("tls.crt"));
    String identityHostname = "account-service.pr-42.svc.cluster.local";

    try (SSLServerSocket server =
        (SSLServerSocket)
            ServedEnvironmentProbe.grpcSslContext(material, trustAnchor)
                .getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      server.setNeedClientAuth(true);
      var serverParameters = server.getSSLParameters();
      serverParameters.setApplicationProtocols(new String[] {"h2"});
      server.setSSLParameters(serverParameters);
      CompletableFuture<Void> accepted =
          CompletableFuture.runAsync(
              () -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                  peer.startHandshake();
                } catch (Exception exception) {
                  throw new IllegalStateException(exception);
                }
              });

      try (SSLSocket client =
          ServedEnvironmentProbe.openGrpcTlsSocket(
              InetAddress.getLoopbackAddress().getHostAddress(),
              identityHostname,
              server.getLocalPort(),
              leaf,
              material,
              trustAnchor)) {
        assertNotNull(client);
      }
      accepted.get(10, TimeUnit.SECONDS);
    }
  }

  private static Secret generatedMaterial(EnvironmentIdentityPlan plan) throws Exception {
    Method generate =
        GrpcTransportBundleGenerator.class.getDeclaredMethod(
            "generate", EnvironmentIdentityPlan.class);
    generate.setAccessible(true);
    return (Secret) generate.invoke(new GrpcTransportBundleGenerator(), plan);
  }

  private static String fingerprint(String encodedCertificate) throws Exception {
    X509Certificate certificate =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(encodedCertificate)));
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
    StringBuilder result = new StringBuilder(64);
    for (byte value : digest) {
      result.append(String.format(Locale.ROOT, "%02x", value));
    }
    return result.toString();
  }
}
