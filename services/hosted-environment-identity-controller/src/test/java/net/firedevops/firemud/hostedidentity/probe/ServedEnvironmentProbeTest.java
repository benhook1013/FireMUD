package net.firedevops.firemud.hostedidentity.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.GrpcMaterialFixture;
import org.junit.jupiter.api.Test;

class ServedEnvironmentProbeTest {
  @Test
  void totalDeadlineLeavesSmallHeadroomBeyondAllEndpointBudgets() {
    assertEquals(Duration.ofSeconds(14), ServedEnvironmentProbe.TOTAL_PROBE_TIMEOUT);
  }

  @Test
  void acceptsExactHttp10AndHttp11StatusLines() throws Exception {
    assertEquals(200, readStatus("HTTP/1.0 200 OK\r\n"));
    assertEquals(204, readStatus("HTTP/1.1 204\r\n"));
  }

  @Test
  void acceptsHttpStatusLineAtExactMaximumLength() throws Exception {
    assertEquals(200, readStatus("HTTP/1.1 200 " + "a".repeat(243) + "\r\n"));
  }

  @Test
  void oversizedHttpStatusLineFailsClosed() throws Exception {
    assertEquals(-1, readStatus("HTTP/1.1 200 " + "a".repeat(244) + "\r\n"));
  }

  @Test
  void repeatedHttpPrefixStatusLineFailsClosed() throws Exception {
    assertEquals(-1, readStatus("HTTP/".repeat(40) + "1.1 200 OK\r\n"));
  }

  @Test
  void malformedHttpStatusLinesFailClosed() throws Exception {
    assertEquals(-1, readStatus("HTTP/2 200 OK\r\n"));
    assertEquals(-1, readStatus("HTTP/1.1 20x OK\r\n"));
    assertEquals(-1, readStatus("HTTP/1.1 2000 OK\r\n"));
    assertEquals(-1, readStatus("HTTP/1.1 200 OK\n"));
    assertEquals(-1, readStatus("HTTP/1.1 200 OK"));
  }

  @Test
  void httpStatusCodeMustBeWithinTheStandardThreeDigitRange() throws Exception {
    assertEquals(-1, readStatus("HTTP/1.1 000 OK\r\n"));
    assertEquals(-1, readStatus("HTTP/1.1 099 OK\r\n"));
    assertEquals(100, readStatus("HTTP/1.1 100 Continue\r\n"));
    assertEquals(599, readStatus("HTTP/1.1 599 Error\r\n"));
    assertEquals(-1, readStatus("HTTP/1.1 600 Error\r\n"));
  }

  @Test
  void readinessProbeRequiresBridgeAndInternalGrpcAcceptanceAfterPublicEndpoints() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);
    ServedEnvironmentProbe.EndpointProbe ready =
        (hostname, port) -> new ServedEnvironmentProbe.ProbeResult(true, "ready");
    ServedEnvironmentProbe.EndpointProbe rejected =
        (hostname, port) -> new ServedEnvironmentProbe.ProbeResult(false, "rejected");

    assertEquals(
        "https-rejected", probe.probe(plan, 32001, rejected, ready, rejected, rejected).reason());
    assertEquals(
        "telnet-rejected", probe.probe(plan, 32001, ready, rejected, rejected, rejected).reason());
    assertEquals(
        "bridge-rejected", probe.probe(plan, 32001, ready, ready, rejected, ready).reason());
    assertEquals("grpc-rejected", probe.probe(plan, 32001, ready, ready, ready, rejected).reason());
    assertEquals(
        "served-bridge-and-grpc-accepted",
        probe.probe(plan, 32001, ready, ready, ready, ready).reason());
  }

  @Test
  void failureClassificationUsesStableProbeNamesRatherThanListPositions() {
    ServedEnvironmentProbe.ProbeResult rejected =
        new ServedEnvironmentProbe.ProbeResult(false, "rejected");

    assertEquals(
        List.of("grpc-rejected", "https-rejected", "bridge-rejected", "telnet-rejected"),
        List.of(
                ServedEnvironmentProbe.ProbeName.GRPC,
                ServedEnvironmentProbe.ProbeName.HTTPS,
                ServedEnvironmentProbe.ProbeName.BRIDGE,
                ServedEnvironmentProbe.ProbeName.TELNET)
            .stream()
            .map(name -> ServedEnvironmentProbe.prefixedResult(name, rejected).reason())
            .toList());
    assertEquals(
        "grpc-material-or-configuration-invalid",
        ServedEnvironmentProbe.prefixedResult(
                ServedEnvironmentProbe.ProbeName.GRPC,
                new ServedEnvironmentProbe.ProbeResult(
                    false, "grpc-material-or-configuration-invalid"))
            .reason());
  }

  @Test
  void readinessProbeSuppliesEachDerivedEndpointToItsProbeSeam() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    List<String> endpoints = Collections.synchronizedList(new ArrayList<>());
    ServedEnvironmentProbe.EndpointProbe recordingProbe =
        (hostname, port) -> {
          endpoints.add(hostname + ":" + port);
          return new ServedEnvironmentProbe.ProbeResult(true, "ready");
        };

    new ServedEnvironmentProbe(properties)
        .probe(plan, 32001, recordingProbe, recordingProbe, recordingProbe, recordingProbe);

    assertEquals(
        Set.of(
            "pr-42.preview.firedevops.net:443",
            "pr-42.preview.firedevops.net:32001",
            "spring-cloud-gateway-mtls.pr-42.svc.cluster.local:443",
            "account-service.pr-42.svc.cluster.local:6565"),
        Set.copyOf(endpoints));
    assertEquals(4, endpoints.size());
  }

  @Test
  void readinessProbeRunsEveryEndpointConcurrentlyWithinOneDeadline() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);
    CountDownLatch started = new CountDownLatch(4);
    EndpointProbeState state = new EndpointProbeState(started);
    ServedEnvironmentProbe.EndpointProbe endpoint = state::check;

    ServedEnvironmentProbe.ProbeResult result =
        probe.probe(plan, 32001, endpoint, endpoint, endpoint, endpoint, Duration.ofSeconds(1));

    assertEquals("served-bridge-and-grpc-accepted", result.reason());
    assertEquals(4, state.calls.get());
    assertEquals(0, started.getCount());
  }

  @Test
  void readinessProbeCancelsAndClosesEveryOutstandingEndpointAttemptAtDeadline() throws Exception {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch interrupted = new CountDownLatch(4);
    List<SSLSocket> openSockets = Collections.synchronizedList(new ArrayList<>());
    ServedEnvironmentProbe.EndpointProbe endpoint =
        (hostname, port) -> {
          SSLSocket openSocket = mock(SSLSocket.class);
          openSockets.add(openSocket);
          ServedEnvironmentProbe.trackSocket(openSocket);
          started.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            interrupted.countDown();
            return new ServedEnvironmentProbe.ProbeResult(false, "interrupted");
          }
          return new ServedEnvironmentProbe.ProbeResult(true, "ready");
        };

    ServedEnvironmentProbe.ProbeResult result =
        probe.probe(plan, 32001, endpoint, endpoint, endpoint, endpoint, Duration.ofSeconds(1));

    assertEquals("probe-deadline-exceeded", result.reason());
    assertTrue(started.await(1, TimeUnit.SECONDS));
    assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    assertEquals(4, openSockets.size());
    for (SSLSocket openSocket : openSockets) {
      verify(openSocket).close();
    }
  }

  @Test
  void readinessProbePreservesUnexpectedProbeFailures() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    ServedEnvironmentProbe.EndpointProbe failed =
        (hostname, port) -> {
          throw new IllegalStateException("probe failed");
        };
    ServedEnvironmentProbe.EndpointProbe ready =
        (hostname, port) -> new ServedEnvironmentProbe.ProbeResult(true, "ready");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new ServedEnvironmentProbe(properties)
                    .probe(plan, 32001, failed, ready, ready, ready, Duration.ofSeconds(1)));

    assertEquals("probe failed", failure.getMessage());
  }

  @Test
  void injectedReadinessProbeBoundsMissingFixedGrpcConsumerConfiguration() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    EnvironmentIdentityPlan missingProbeConsumer = withConsumers(plan, "tcp-proxy-service");
    ServedEnvironmentProbe.EndpointProbe ready =
        (hostname, port) -> new ServedEnvironmentProbe.ProbeResult(true, "ready");

    assertEquals(
        "grpc-material-or-configuration-invalid",
        new ServedEnvironmentProbe(properties)
            .probe(missingProbeConsumer, 32001, ready, ready, ready, ready)
            .reason());
  }

  @Test
  void internalTlsProbeDistinguishesMaterialErrorsFromConnectionFailures() {
    assertEquals(
        "material-or-configuration-invalid",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  throw new IllegalArgumentException("invalid material");
                },
                "mtls-handshake")
            .reason());
    assertEquals(
        "material-or-configuration-invalid",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  ServedEnvironmentProbe.grpcSslContext(new Secret(), null);
                  return null;
                },
                "mtls-handshake")
            .reason());
    assertEquals(
        "connection-failed",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  throw new IOException("connect failed");
                },
                "mtls-handshake")
            .reason());
    assertEquals(
        "handshake-policy-rejected",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  throw new ServedEnvironmentProbe.HandshakePolicyRejectedException(
                      "gRPC endpoint did not negotiate HTTP/2");
                },
                "mtls-handshake")
            .reason());
    assertEquals(
        "connection-failed",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  throw new IllegalStateException("other state failure");
                },
                "mtls-handshake")
            .reason());
  }

  @Test
  void malformedGrpcTrustAnchorIsRejectedBeforeAbsentOrMalformedMaterial() {
    IllegalArgumentException absentMaterial =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServedEnvironmentProbe.grpcSslContext(null, "not-a-trust-anchor"));
    Secret malformedMaterial =
        new SecretBuilder()
            .withData(
                Map.of(
                    "tls.crt", "not-base64",
                    "tls.key", "not-base64",
                    "ca.crt", "not-base64"))
            .build();
    IllegalArgumentException unreadableMaterial =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServedEnvironmentProbe.grpcSslContext(malformedMaterial, "not-a-trust-anchor"));

    assertEquals("configured gRPC trust anchor is invalid", absentMaterial.getMessage());
    assertEquals("configured gRPC trust anchor is invalid", unreadableMaterial.getMessage());
  }

  @Test
  void servedProbeClosesSocketWhenSetupFailsBeforeOwnershipTransfer() throws Exception {
    SSLSocket socket = mock(SSLSocket.class);
    doThrow(new IOException("connect failed"))
        .when(socket)
        .connect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                ServedEnvironmentProbe.openTlsSocket(
                    "pr-42.example.test", 443, "1".repeat(64), socket));
    assertEquals("connect failed", failure.getMessage());
    verify(socket).close();
  }

  @Test
  void internalGrpcProbeCompletesMutualTlsWithFixedCaHostnameAndLeafPin() throws Exception {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    Secret material = generatedMaterial(plan);
    String trustAnchor = fingerprint(material.getData().get("ca.crt"));
    String leaf = fingerprint(material.getData().get("tls.crt"));
    String identityHostname = "account-service.pr-42.svc.cluster.local";

    try (SSLServerSocket server = mutualTlsServer(material, trustAnchor, "h2")) {
      CompletableFuture<Void> accepted = acceptOne(server);
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

  @Test
  void internalBridgeProbeDoesNotRequireAnApplicationProtocol() throws Exception {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret material = generatedMaterial(plan);
    String trustAnchor = fingerprint(material.getData().get("ca.crt"));
    String leaf = fingerprint(material.getData().get("tls.crt"));
    String identityHostname = "account-service.pr-42.svc.cluster.local";

    try (SSLServerSocket server = mutualTlsServer(material, trustAnchor)) {
      CompletableFuture<Void> accepted = acceptOne(server);
      try (SSLSocket client =
          ServedEnvironmentProbe.openBridgeTlsSocket(
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

  @Test
  void internalGrpcProbeRejectsAHandshakeWithoutHttp2() throws Exception {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret material = generatedMaterial(plan);
    String trustAnchor = fingerprint(material.getData().get("ca.crt"));
    String leaf = fingerprint(material.getData().get("tls.crt"));
    String identityHostname = "account-service.pr-42.svc.cluster.local";

    try (SSLServerSocket server = mutualTlsServer(material, trustAnchor)) {
      CompletableFuture<Void> accepted = acceptOne(server);
      ServedEnvironmentProbe.HandshakePolicyRejectedException failure =
          assertThrows(
              ServedEnvironmentProbe.HandshakePolicyRejectedException.class,
              () ->
                  ServedEnvironmentProbe.openGrpcTlsSocket(
                      InetAddress.getLoopbackAddress().getHostAddress(),
                      identityHostname,
                      server.getLocalPort(),
                      leaf,
                      material,
                      trustAnchor));
      assertEquals("gRPC endpoint did not negotiate HTTP/2", failure.getMessage());
      accepted.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void malformedOrUnsupportedGrpcPrivateKeysAreMaterialErrors() throws Exception {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");

    assertGrpcPrivateKeyRejected(plan, encodedPem(new byte[] {1, 2, 3}));

    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    assertGrpcPrivateKeyRejected(
        plan, encodedPem(keyPairGenerator.generateKeyPair().getPrivate().getEncoded()));
  }

  @Test
  void mismatchedGrpcPrivateKeyIsAMaterialError() throws Exception {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);

    Secret material = generatedMaterial(plan);
    material
        .getData()
        .put("tls.key", encodedPem(keyPairGenerator.generateKeyPair().getPrivate().getEncoded()));
    String trustAnchor = fingerprint(material.getData().get("ca.crt"));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServedEnvironmentProbe.grpcSslContext(material, trustAnchor));
    assertEquals("gRPC private key does not match the leaf certificate", failure.getMessage());
    assertEquals(
        "material-or-configuration-invalid",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  ServedEnvironmentProbe.grpcSslContext(material, trustAnchor);
                  return null;
                },
                "mtls-handshake")
            .reason());
  }

  private static void assertGrpcPrivateKeyRejected(
      EnvironmentIdentityPlan plan, String encodedPrivateKey) throws Exception {
    Secret material = generatedMaterial(plan);
    material.getData().put("tls.key", encodedPrivateKey);
    String trustAnchor = fingerprint(material.getData().get("ca.crt"));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServedEnvironmentProbe.grpcSslContext(material, trustAnchor));
    assertEquals("gRPC private key is not valid RSA PKCS#8", failure.getMessage());
    assertEquals(
        "material-or-configuration-invalid",
        ServedEnvironmentProbe.internalTlsProbe(
                () -> {
                  ServedEnvironmentProbe.grpcSslContext(material, trustAnchor);
                  return null;
                },
                "mtls-handshake")
            .reason());
  }

  private static Secret generatedMaterial(EnvironmentIdentityPlan plan) throws Exception {
    return GrpcMaterialFixture.generate(plan);
  }

  private static SSLServerSocket mutualTlsServer(
      Secret material, String trustAnchor, String... applicationProtocols) throws Exception {
    SSLServerSocket server =
        (SSLServerSocket)
            ServedEnvironmentProbe.grpcSslContext(material, trustAnchor)
                .getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
    server.setNeedClientAuth(true);
    if (applicationProtocols.length > 0) {
      var serverParameters = server.getSSLParameters();
      serverParameters.setApplicationProtocols(applicationProtocols);
      server.setSSLParameters(serverParameters);
    }
    return server;
  }

  private static CompletableFuture<Void> acceptOne(SSLServerSocket server) {
    return CompletableFuture.runAsync(
        () -> {
          try (SSLSocket peer = (SSLSocket) server.accept()) {
            peer.startHandshake();
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          }
        });
  }

  private static EnvironmentIdentityPlan withConsumers(
      EnvironmentIdentityPlan plan, String... consumers) {
    return plan.withGrpcConsumers(List.of(consumers));
  }

  private static int readStatus(String statusLine) throws Exception {
    return ServedEnvironmentProbe.readHttpStatusCode(
        new ByteArrayInputStream(statusLine.getBytes(StandardCharsets.ISO_8859_1)));
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

  private static String encodedPem(byte[] der) {
    String body = Base64.getEncoder().encodeToString(der);
    String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
  }

  private static final class EndpointProbeState {
    private final CountDownLatch started;
    private final AtomicInteger calls = new AtomicInteger();

    private EndpointProbeState(CountDownLatch started) {
      this.started = started;
    }

    private ServedEnvironmentProbe.ProbeResult check(String hostname, int port) {
      calls.incrementAndGet();
      started.countDown();
      try {
        if (!started.await(5, TimeUnit.SECONDS)) {
          return new ServedEnvironmentProbe.ProbeResult(false, "not-concurrent");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return new ServedEnvironmentProbe.ProbeResult(false, "interrupted");
      }
      return new ServedEnvironmentProbe.ProbeResult(true, "ready");
    }
  }
}
