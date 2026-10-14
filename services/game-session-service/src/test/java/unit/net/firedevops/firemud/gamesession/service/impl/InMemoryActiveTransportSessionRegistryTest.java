package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class InMemoryActiveTransportSessionRegistryTest {
  @Test
  void currentRawTransportIsRemovedAndReportsOwnershipOnce() {
    var registry = new InMemoryActiveTransportSessionRegistry();
    WebSocketSession session = session("current");
    registry.register(41L, session);
    assertThat(registry.unregister(41L, session)).isTrue();
    assertThat(registry.find(41L)).isEmpty();
    assertThat(registry.unregister(41L, session)).isFalse();
  }

  @Test
  void registeredDecoratorIsAlsoAnExactOwnerForDeliveryCallers() {
    var registry = new InMemoryActiveTransportSessionRegistry();
    registry.register(41L, session("current"));
    WebSocketSession decorated = registry.find(41L).orElseThrow();
    assertThat(registry.unregister(41L, decorated)).isTrue();
    assertThat(registry.find(41L)).isEmpty();
  }

  @Test
  void rejectedAndReplacedTransportsCannotRemoveTheIncumbent() {
    var registry = new InMemoryActiveTransportSessionRegistry();
    WebSocketSession old = session("old");
    WebSocketSession current = session("current");
    registry.register(41L, old);
    registry.register(41L, current);
    WebSocketSession incumbent = registry.find(41L).orElseThrow();
    assertThat(registry.unregister(41L, old)).isFalse();
    assertThat(registry.unregister(41L, session("rejected"))).isFalse();
    assertThat(registry.unregister(41L, session("current"))).isFalse();
    assertThat(registry.find(41L)).contains(incumbent);
    assertThat(registry.unregister(41L, current)).isTrue();
  }

  private WebSocketSession session(String id) {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    return session;
  }
}
