package net.firedevops.firemud.springcloudgateway.filter;

import java.util.UUID;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ServerWebExchange;

/** Process-private proof produced only after this Gateway authenticates the TCP Proxy hop. */
public final class VerifiedTcpProxyOrigin {
  private static final String ATTRIBUTE = VerifiedTcpProxyOrigin.class.getName();
  private final String clientAddress;
  private final UUID proxyConnectionId;

  private VerifiedTcpProxyOrigin(String clientAddress, UUID proxyConnectionId) {
    this.clientAddress = clientAddress;
    this.proxyConnectionId = proxyConnectionId;
  }

  static void retain(ServerWebExchange exchange, String address, String connectionId) {
    UUID id = UUID.fromString(connectionId);
    if (!id.toString().equals(connectionId) || id.version() != 4 || id.variant() != 2)
      throw new IllegalArgumentException("Canonical TCP Proxy connection UUID required");
    exchange.getAttributes().put(ATTRIBUTE, new VerifiedTcpProxyOrigin(address, id));
  }

  /** Carries the private proof across upgrade without interpreting any request header as proof. */
  public static WebSocketHandler bind(ServerWebExchange exchange, WebSocketHandler handler) {
    Object proof = exchange.getAttributes().get(ATTRIBUTE);
    return session -> {
      session.getAttributes().remove(ATTRIBUTE);
      if (proof instanceof VerifiedTcpProxyOrigin origin)
        session.getAttributes().put(ATTRIBUTE, origin);
      return handler.handle(session);
    };
  }

  public static VerifiedTcpProxyOrigin from(WebSocketSession session) {
    Object value = session.getAttributes().get(ATTRIBUTE);
    return value instanceof VerifiedTcpProxyOrigin origin ? origin : null;
  }

  public String clientAddress() {
    return clientAddress;
  }

  public UUID proxyConnectionId() {
    return proxyConnectionId;
  }
}
