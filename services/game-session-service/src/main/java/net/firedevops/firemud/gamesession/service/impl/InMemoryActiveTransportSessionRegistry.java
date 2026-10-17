package net.firedevops.firemud.gamesession.service.impl;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Local registry of active WebSocket-backed transport sessions on this Game Session instance. */
@Service
public final class InMemoryActiveTransportSessionRegistry
    implements ActiveTransportSessionRegistry {
  private static final int SEND_TIME_LIMIT_MS = 10_000;
  private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

  private final ConcurrentMap<Long, RegisteredSession> sessions = new ConcurrentHashMap<>();

  @Override
  public void register(long sessionId, WebSocketSession session) {
    sessions.put(
        sessionId,
        new RegisteredSession(
            session.getId(),
            new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES)));
  }

  @Override
  public boolean unregister(long sessionId, WebSocketSession session) {
    RegisteredSession registered = sessions.get(sessionId);
    if (registered == null
        || registered.session() != session && registered.session().getDelegate() != session) {
      return false;
    }
    // Conditional removal binds the result to this exact snapshot; a concurrent replacement wins.
    return sessions.remove(sessionId, registered);
  }

  @Override
  public Optional<WebSocketSession> find(long sessionId) {
    return Optional.ofNullable(sessions.get(sessionId)).map(RegisteredSession::session);
  }

  private record RegisteredSession(
      String websocketSessionId, ConcurrentWebSocketSessionDecorator session) {}
}
