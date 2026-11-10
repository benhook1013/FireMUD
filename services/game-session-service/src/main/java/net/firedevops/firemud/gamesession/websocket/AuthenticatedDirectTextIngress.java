package net.firedevops.firemud.gamesession.websocket;

import java.security.cert.X509Certificate;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

/** Immutable transport proof minted only from the dedicated connector's authenticated TLS peer. */
public final class AuthenticatedDirectTextIngress {
  static final String ATTRIBUTE = AuthenticatedDirectTextIngress.class.getName();
  private final ObservedDirectTextTransport transport;
  private final String clientAddress;
  private final UUID backendGeneration = UUID.randomUUID();

  private AuthenticatedDirectTextIngress(ObservedDirectTextTransport transport, String address) {
    this.transport = transport;
    this.clientAddress = address;
  }

  static AuthenticatedDirectTextIngress authenticate(
      ServerHttpRequest request, int dedicatedPort, String namespace, String sessionId) {
    if (!(request instanceof ServletServerHttpRequest servlet)
        || !servlet.getServletRequest().isSecure()
        || servlet.getServletRequest().getLocalPort() != dedicatedPort
        || !"/ws/game/tcp".equals(request.getURI().getPath())
        || !GrpcPeerIdentity.isValidNamespace(namespace)) throw unavailable();
    Object value =
        servlet.getServletRequest().getAttribute("jakarta.servlet.request.X509Certificate");
    if (!(value instanceof X509Certificate[] chain) || chain.length == 0) throw unavailable();
    try {
      chain[0].checkValidity();
      if (chain[0].getExtendedKeyUsage() == null
          || !chain[0].getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")
          || GrpcPeerIdentity.fromCertificate(chain[0])
              .filter(
                  peer -> peer.isService("spring-cloud-gateway") && peer.isInNamespace(namespace))
              .isEmpty()) throw unavailable();
    } catch (java.security.cert.CertificateException invalid) {
      throw unavailable();
    }
    var headers = request.getHeaders();
    String address = singleHeader(headers, "X-Client-IP");
    String proxyConnectionId = singleHeader(headers, "X-Proxy-Connection-Id");
    String mode = singleHeader(headers, "X-Firemud-Connection-Mode");
    if (address.length() > 64
        || address.contains("%")
        || !address.matches("[0-9a-fA-F:.]+")
        || !"trusted_tcp_proxy".equals(mode)
        || headers.containsHeader("X-Firemud-Transport-Session-Id")
        || headers.containsHeader("X-Firemud-Connect-Context")) throw unavailable();
    String canonicalAddress = java.net.InetAddress.ofLiteral(address).getHostAddress();
    var observed =
        ObservedDirectTextTransport.fromHandshake(mode, sessionId, proxyConnectionId)
            .orElseThrow(AuthenticatedDirectTextIngress::unavailable);
    return new AuthenticatedDirectTextIngress(observed, canonicalAddress);
  }

  private static String singleHeader(org.springframework.http.HttpHeaders headers, String name) {
    var values = headers.get(name);
    if (values == null || values.size() != 1) throw unavailable();
    String value = values.getFirst();
    if (value == null) throw unavailable();
    return value;
  }

  public ObservedDirectTextTransport transport() {
    return transport;
  }

  public String clientAddress() {
    return clientAddress;
  }

  public UUID backendGeneration() {
    return backendGeneration;
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Authenticated Gateway TCP provenance required");
  }
}
