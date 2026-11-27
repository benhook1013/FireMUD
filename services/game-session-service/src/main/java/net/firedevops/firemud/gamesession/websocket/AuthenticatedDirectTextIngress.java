package net.firedevops.firemud.gamesession.websocket;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

/** Immutable transport proof minted only from the dedicated connector's authenticated TLS peer. */
public final class AuthenticatedDirectTextIngress {
  static final String ATTRIBUTE = AuthenticatedDirectTextIngress.class.getName();
  static final String TCP_PATH = "/ws/game/tcp";
  static final String FIXTURE_WEBSOCKET_PATH = "/ws/game/run-owned-fixture";
  private final ObservedDirectTextTransport transport;
  private final String clientAddress;
  private final GameplayLoginTransportClass loginTransport;
  private final UUID backendGeneration = UUID.randomUUID();

  private AuthenticatedDirectTextIngress(
      ObservedDirectTextTransport transport, String address, GameplayLoginTransportClass mode) {
    this.transport = transport;
    this.clientAddress = address;
    this.loginTransport = mode;
  }

  static AuthenticatedDirectTextIngress authenticate(
      ServerHttpRequest request, int dedicatedPort, String namespace, String sessionId) {
    if (!TCP_PATH.equals(request.getURI().getPath())
        || !GrpcPeerIdentity.isValidNamespace(namespace)
        || GrpcPeerIdentity.fromCertificate(requireClientCertificate(request, dedicatedPort))
            .filter(peer -> peer.isService("spring-cloud-gateway") && peer.isInNamespace(namespace))
            .isEmpty()) {
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
    return new AuthenticatedDirectTextIngress(
        observed,
        canonicalAddress,
        GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY);
  }

  static AuthenticatedDirectTextIngress authenticateFixtureWebSocket(
      ServletServerHttpRequest request,
      int dedicatedPort,
      String certificateSha256,
      String sessionId,
      UUID connectionId) {
    if (!FIXTURE_WEBSOCKET_PATH.equals(request.getURI().getPath())) throw unavailable();
    var certificate = requireClientCertificate(request, dedicatedPort);
    try {
      byte[] actualDigest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
      if (!MessageDigest.isEqual(actualDigest, HexFormat.of().parseHex(certificateSha256))) {
        throw unavailable();
      }
    } catch (CertificateException | NoSuchAlgorithmException invalid) {
      throw unavailable();
    }
    String address =
        java.net.InetAddress.ofLiteral(request.getServletRequest().getRemoteAddr())
            .getHostAddress();
    return new AuthenticatedDirectTextIngress(
        new ObservedDirectTextTransport(sessionId, connectionId),
        address,
        GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_RUN_OWNED_FIXTURE_WEBSOCKET);
  }

  private static X509Certificate requireClientCertificate(
      ServerHttpRequest request, int dedicatedPort) {
    if (!(request instanceof ServletServerHttpRequest servlet)
        || !servlet.getServletRequest().isSecure()
        || servlet.getServletRequest().getLocalPort() != dedicatedPort) throw unavailable();
    Object value =
        servlet.getServletRequest().getAttribute("jakarta.servlet.request.X509Certificate");
    if (!(value instanceof X509Certificate[] chain) || chain.length == 0) throw unavailable();
    try {
      chain[0].checkValidity();
      if (chain[0].getExtendedKeyUsage() == null
          || !chain[0].getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")) throw unavailable();
      return chain[0];
    } catch (CertificateException invalid) {
      throw unavailable();
    }
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

  public GameplayLoginTransportClass loginTransport() {
    return loginTransport;
  }

  public UUID backendGeneration() {
    return backendGeneration;
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Authenticated gameplay transport provenance required");
  }
}
