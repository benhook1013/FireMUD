package net.firedevops.firemud.gamesession.websocket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Copies required request headers into stable WebSocket session attributes during upgrade. */
@Component
public class GameSessionWebSocketHandshakeInterceptor implements HandshakeInterceptor {
  static final String GAME_INSTANCE_HEADER = "X-Game-Instance-Id";
  static final String PROXY_CONNECTION_HEADER = "X-Proxy-Connection-Id";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String SOLO_TICK_HEADER = "X-Requires-Solo-Tick";
  static final String CONNECTION_MODE_HEADER = "X-Firemud-Connection-Mode";
  static final String CONNECT_CONTEXT_HEADER = "X-Firemud-Connect-Context";
  static final String SESSION_ID_ATTR = "firemud.websocket.sessionId";
  static final String BOOTSTRAP_GAME_INSTANCE_ATTR = "firemud.websocket.bootstrapGameInstanceId";
  static final String TENANT_ID_ATTR = "firemud.websocket.tenantId";
  static final String SOLO_TICK_ATTR = "firemud.websocket.requiresSoloTick";
  static final String CONNECTION_MODE_ATTR = "firemud.websocket.connectionMode";
  static final String CONNECT_CONTEXT_ATTR = "firemud.websocket.connectContext";

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String bootstrapGameInstanceId = request.getHeaders().getFirst(GAME_INSTANCE_HEADER);
    String sessionId =
        deriveTransportSessionId(
            request.getHeaders().getFirst(PROXY_CONNECTION_HEADER),
            bootstrapGameInstanceId,
            request.getHeaders().getFirst(CONNECT_CONTEXT_HEADER));
    attributes.put(SESSION_ID_ATTR, sessionId);
    attributes.put(BOOTSTRAP_GAME_INSTANCE_ATTR, bootstrapGameInstanceId);
    attributes.put(TENANT_ID_ATTR, request.getHeaders().getFirst(TENANT_HEADER));
    attributes.put(SOLO_TICK_ATTR, request.getHeaders().getFirst(SOLO_TICK_HEADER));
    attributes.put(CONNECTION_MODE_ATTR, request.getHeaders().getFirst(CONNECTION_MODE_HEADER));
    attributes.put(CONNECT_CONTEXT_ATTR, request.getHeaders().getFirst(CONNECT_CONTEXT_HEADER));
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}

  private String deriveTransportSessionId(
      String proxyConnectionId, String bootstrapGameInstanceId, String connectContext) {
    if (proxyConnectionId != null && !proxyConnectionId.isBlank()) {
      return Long.toUnsignedString(stablePositiveLong(proxyConnectionId));
    }
    if (connectContext != null && !connectContext.isBlank()) {
      return Long.toUnsignedString(stablePositiveLong(connectContext));
    }
    return bootstrapGameInstanceId;
  }

  private long stablePositiveLong(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      long candidate = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
      return candidate == Long.MIN_VALUE ? 0L : Math.abs(candidate);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
