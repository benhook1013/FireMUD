package net.firedevops.firemud.gamesession.websocket;

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
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String SOLO_TICK_HEADER = "X-Requires-Solo-Tick";
  static final String SESSION_ID_ATTR = "firemud.websocket.sessionId";
  static final String TENANT_ID_ATTR = "firemud.websocket.tenantId";
  static final String SOLO_TICK_ATTR = "firemud.websocket.requiresSoloTick";

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String sessionId = request.getHeaders().getFirst(GAME_INSTANCE_HEADER);
    attributes.put(SESSION_ID_ATTR, sessionId);
    attributes.put(TENANT_ID_ATTR, request.getHeaders().getFirst(TENANT_HEADER));
    attributes.put(SOLO_TICK_ATTR, request.getHeaders().getFirst(SOLO_TICK_HEADER));
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}
}
