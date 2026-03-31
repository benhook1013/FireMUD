package net.firedevops.firemud.gamesession.service;

import java.util.Optional;
import org.springframework.web.socket.WebSocketSession;

/** Tracks live WebSocket-backed transport sessions on the current Game Session instance. */
public interface ActiveTransportSessionRegistry {
  void register(long sessionId, WebSocketSession session);

  void unregister(long sessionId, WebSocketSession session);

  Optional<WebSocketSession> find(long sessionId);
}
