package net.firedevops.firemud.gamesession.websocket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Copies required request headers into stable WebSocket session attributes during upgrade. */
@Component
public class GameSessionWebSocketHandshakeInterceptor implements HandshakeInterceptor {
  static final String GAME_INSTANCE_HEADER = "X-Game-Instance-Id";
  static final String PROXY_CONNECTION_HEADER = "X-Proxy-Connection-Id";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String WORLD_SLUG_HEADER = "X-World-Slug";
  static final String REALM_SLUG_HEADER = "X-Realm-Slug";
  static final String POINTER_VERSION_HEADER = "X-Pointer-Version";
  static final String SOLO_TICK_HEADER = "X-Requires-Solo-Tick";
  static final String CONNECTION_MODE_HEADER = "X-Firemud-Connection-Mode";
  static final String CONNECT_CONTEXT_HEADER = "X-Firemud-Connect-Context";
  static final String TRANSPORT_SESSION_HEADER = "X-Firemud-Transport-Session-Id";
  static final String LOCALE_HEADER = "X-Firemud-Locale";
  static final String ACCEPT_LANGUAGE_HEADER = "Accept-Language";
  static final String SESSION_ID_ATTR = "firemud.websocket.sessionId";
  static final String BOOTSTRAP_GAME_INSTANCE_ATTR = "firemud.websocket.bootstrapGameInstanceId";
  static final String TENANT_ID_ATTR = "firemud.websocket.tenantId";
  static final String WORLD_SLUG_ATTR = "firemud.websocket.worldSlug";
  static final String REALM_SLUG_ATTR = "firemud.websocket.realmSlug";
  static final String POINTER_VERSION_ATTR = "firemud.websocket.pointerVersion";
  static final String SOLO_TICK_ATTR = "firemud.websocket.requiresSoloTick";
  static final String CONNECTION_MODE_ATTR = "firemud.websocket.connectionMode";
  static final String CONNECT_CONTEXT_ATTR = "firemud.websocket.connectContext";
  static final String LOCALE_ATTR = "firemud.websocket.localeTag";

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String bootstrapGameInstanceId = request.getHeaders().getFirst(GAME_INSTANCE_HEADER);
    String sessionId =
        deriveTransportSessionId(
            request.getHeaders().getFirst(TRANSPORT_SESSION_HEADER),
            request.getHeaders().getFirst(PROXY_CONNECTION_HEADER),
            bootstrapGameInstanceId,
            request.getHeaders().getFirst(CONNECT_CONTEXT_HEADER));
    attributes.put(SESSION_ID_ATTR, sessionId);
    attributes.put(BOOTSTRAP_GAME_INSTANCE_ATTR, bootstrapGameInstanceId);
    attributes.put(TENANT_ID_ATTR, request.getHeaders().getFirst(TENANT_HEADER));
    attributes.put(WORLD_SLUG_ATTR, request.getHeaders().getFirst(WORLD_SLUG_HEADER));
    attributes.put(REALM_SLUG_ATTR, request.getHeaders().getFirst(REALM_SLUG_HEADER));
    attributes.put(POINTER_VERSION_ATTR, request.getHeaders().getFirst(POINTER_VERSION_HEADER));
    attributes.put(SOLO_TICK_ATTR, request.getHeaders().getFirst(SOLO_TICK_HEADER));
    attributes.put(CONNECTION_MODE_ATTR, request.getHeaders().getFirst(CONNECTION_MODE_HEADER));
    attributes.put(CONNECT_CONTEXT_ATTR, request.getHeaders().getFirst(CONNECT_CONTEXT_HEADER));
    attributes.put(
        LOCALE_ATTR,
        resolveLocaleTag(
            request.getHeaders().getFirst(LOCALE_HEADER),
            request.getHeaders().getFirst(ACCEPT_LANGUAGE_HEADER)));
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}

  private String deriveTransportSessionId(
      String transportSessionId,
      String proxyConnectionId,
      String bootstrapGameInstanceId,
      String connectContext) {
    if (transportSessionId != null && !transportSessionId.isBlank()) {
      return transportSessionId;
    }
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

  private String resolveLocaleTag(String localeHeader, String acceptLanguageHeader) {
    String explicit = normalizeLocaleTag(localeHeader);
    if (explicit != null) {
      return explicit;
    }
    if (!StringUtils.hasText(acceptLanguageHeader)) {
      return null;
    }
    String firstLanguageRange = acceptLanguageHeader.split(",")[0];
    String candidate = firstLanguageRange.split(";")[0].trim();
    return normalizeLocaleTag(candidate);
  }

  private String normalizeLocaleTag(String localeTag) {
    if (!StringUtils.hasText(localeTag)) {
      return null;
    }
    String normalized = Locale.forLanguageTag(localeTag.trim()).toLanguageTag();
    return "und".equals(normalized) ? null : normalized;
  }
}
