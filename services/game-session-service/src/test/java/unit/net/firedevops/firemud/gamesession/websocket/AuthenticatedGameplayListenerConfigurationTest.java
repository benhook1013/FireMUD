package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.http.server.ServletServerHttpRequest;

/** Physical connector and authenticated receiving proof, independent of Account admission. */
class AuthenticatedGameplayListenerConfigurationTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Gameplay listener test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "session", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "gateway", "spiffe://firemud/ns/test/sa/spring-cloud-gateway", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-service", "spiffe://firemud/ns/test/sa/account-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/other/sa/spring-cloud-gateway", false));
  }

  @Test
  void physicalDedicatedConnectorRequiresClientTlsAndExactGatewayIdentity() throws Exception {
    int port;
    try (var reserved = new ServerSocket(0)) {
      port = reserved.getLocalPort();
    }
    var config = new AuthenticatedGameplayListenerConfiguration();
    var bundles = mock(SslBundles.class);
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(null, null);
    var server = pki.identity("session");
    keys.setKeyEntry(
        "session",
        server.privateKey(),
        "test-only".toCharArray(),
        new X509Certificate[] {server.certificate(), pki.caCertificate()});
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("ca", pki.caCertificate());
    when(bundles.getBundle("firemud-grpc"))
        .thenReturn(SslBundle.of(SslStoreBundle.of(keys, "test-only", trust)));
    var factory = new TomcatServletWebServerFactory(0);
    config.authenticatedGameplayConnector(bundles, port, 0).customize(factory);
    var received = new AtomicInteger();
    var filter = config.authenticatedGameplayPathFilter(port);
    var web =
        factory.getWebServer(
            context -> {
              var servlet =
                  context.addServlet(
                      "custody",
                      new HttpServlet() {
                        @Override
                        protected void doGet(
                            HttpServletRequest request, HttpServletResponse response)
                            throws java.io.IOException, jakarta.servlet.ServletException {
                          filter.doFilter(
                              request,
                              response,
                              (incoming, outgoing) -> {
                                try {
                                  AuthenticatedDirectTextIngress.authenticate(
                                      new ServletServerHttpRequest((HttpServletRequest) incoming),
                                      port,
                                      "test",
                                      "42");
                                  received.incrementAndGet();
                                  ((HttpServletResponse) outgoing).setStatus(200);
                                } catch (IllegalArgumentException rejected) {
                                  ((HttpServletResponse) outgoing).setStatus(403);
                                }
                              });
                        }
                      });
              servlet.addMapping("/*");
            });
    web.start();
    try {
      assertThat(call("gateway", port, "/ws/game/tcp").statusCode()).isEqualTo(200);
      assertThat(call("wrong-service", port, "/ws/game/tcp").statusCode()).isEqualTo(403);
      assertThat(call("wrong-namespace", port, "/ws/game/tcp").statusCode()).isEqualTo(403);
      assertThat(call("gateway", port, "/actuator/health").statusCode()).isEqualTo(404);
      assertThatThrownBy(() -> call(null, port, "/ws/game/tcp"))
          .isInstanceOf(java.io.IOException.class);
      try (var publicClient = HttpClient.newHttpClient()) {
        assertThat(
                publicClient
                    .send(
                        HttpRequest.newBuilder(
                                URI.create("http://localhost:" + web.getPort() + "/ws/game/tcp"))
                            .timeout(Duration.ofSeconds(5))
                            .header("X-Firemud-Connection-Mode", "trusted_tcp_proxy")
                            .header("X-Proxy-Connection-Id", "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814")
                            .header("X-Client-IP", "203.0.113.7")
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode())
            .isEqualTo(403);
      }
      assertThat(received).hasValue(1);
    } finally {
      web.stop();
      web.destroy();
    }
  }

  private HttpResponse<String> call(String identity, int port, String path) throws Exception {
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(
        identity == null ? null : pki.keyManagerFactory(pki.identity(identity)).getKeyManagers(),
        pki.trustManagerFactory().getTrustManagers(),
        null);
    try (var client =
        HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
              .timeout(Duration.ofSeconds(5))
              .header("X-Firemud-Connection-Mode", "trusted_tcp_proxy")
              .header("X-Proxy-Connection-Id", "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814")
              .header("X-Client-IP", "203.0.113.7")
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
