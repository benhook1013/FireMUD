package net.firedevops.firemud.springcloudgateway.websocket;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLParameters;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Dedicated Gateway-to-Game Session mTLS transport using the existing workload TLS material. */
public final class AuthenticatedGameplayWebSocketClient implements AutoCloseable {
  private final CommonGrpcClientProperties tls;
  private final String expectedPeer;
  private final String expectedClient;
  private final URI upstream;
  private volatile ReactorNettyWebSocketClient client;
  private TlsCertificateWatcher watcher;

  public AuthenticatedGameplayWebSocketClient(
      CommonGrpcClientProperties properties, String namespace, String upstreamUrl) {
    tls = properties.copy();
    FileBackedGrpcMtlsPolicy.require(
        tls,
        "Authenticated gameplay WebSocket",
        "Workload TLS material required",
        "Authenticated gameplay requires mutual TLS");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Exact gameplay workload namespace required");
    upstream = URI.create(upstreamUrl);
    if (!"wss".equals(upstream.getScheme())
        || upstream.getHost() == null
        || upstream.getUserInfo() != null
        || upstream.getQuery() != null
        || upstream.getFragment() != null
        || !"/ws/game/tcp".equals(upstream.getPath()))
      throw new IllegalArgumentException("Dedicated authenticated gameplay wss endpoint required");
    expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    expectedClient = "spiffe://firemud/ns/" + namespace + "/sa/spring-cloud-gateway";
  }

  public void initialize() throws IOException {
    reload();
    watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(
                Path.of(tls.getCertChain()),
                Path.of(tls.getPrivateKey()),
                Path.of(tls.getCaCert())),
            () -> {
              try {
                reload();
              } catch (IOException failure) {
                throw new IllegalStateException(
                    "Authenticated gameplay TLS reload failed", failure);
              }
            });
  }

  private void reload() throws IOException {
    try (var input = java.nio.file.Files.newInputStream(Path.of(tls.getCertChain()))) {
      var certificate =
          (java.security.cert.X509Certificate)
              java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);
      certificate.checkValidity();
      if (certificate.getExtendedKeyUsage() == null
          || !certificate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")
          || GrpcPeerIdentity.fromCertificate(certificate)
              .filter(peer -> expectedClient.equals(peer.uri()))
              .isEmpty())
        throw new IllegalArgumentException("Exact Gateway workload client identity required");
    } catch (java.security.cert.CertificateException invalid) {
      throw new IOException("Invalid Gateway workload TLS certificate", invalid);
    }
    var context =
        SslContextBuilder.forClient()
            .keyManager(Path.of(tls.getCertChain()).toFile(), Path.of(tls.getPrivateKey()).toFile())
            .trustManager(Path.of(tls.getCaCert()).toFile())
            .protocols("TLSv1.3", "TLSv1.2")
            .build();
    HttpClient http =
        HttpClient.newConnection()
            .secure(
                spec ->
                    spec.sslContext(context)
                        .handlerConfigurator(
                            handler -> {
                              SSLParameters parameters = handler.engine().getSSLParameters();
                              parameters.setEndpointIdentificationAlgorithm("HTTPS");
                              handler.engine().setSSLParameters(parameters);
                            }))
            .doOnRequest(
                (request, connection) -> {
                  SslHandler handler = connection.channel().pipeline().get(SslHandler.class);
                  if (handler == null
                      || GrpcPeerIdentity.fromSslSession(handler.engine().getSession())
                          .filter(identity -> expectedPeer.equals(identity.uri()))
                          .isEmpty())
                    throw new IllegalStateException("Authenticated Game Session peer required");
                });
    client = new ReactorNettyWebSocketClient(http);
  }

  public Mono<Void> execute(HttpHeaders headers, WebSocketHandler handler) {
    return Mono.defer(
        () -> {
          ReactorNettyWebSocketClient current = client;
          if (current == null) {
            return Mono.error(
                new IllegalStateException("Authenticated gameplay client unavailable"));
          }
          return current.execute(upstream, headers, handler);
        });
  }

  public URI upstream() {
    return upstream;
  }

  @Override
  public void close() throws IOException {
    if (watcher != null) watcher.close();
    client = null;
  }
}
