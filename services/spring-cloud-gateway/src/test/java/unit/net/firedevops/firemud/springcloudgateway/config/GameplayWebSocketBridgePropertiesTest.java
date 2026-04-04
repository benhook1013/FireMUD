package net.firedevops.firemud.springcloudgateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GameplayWebSocketBridgePropertiesTest {

  @Test
  void defaultsApplyForBlankAndNonPositiveValues() {
    GameplayWebSocketBridgeProperties properties =
        new GameplayWebSocketBridgeProperties(" ", 0, 0L, 0);

    assertEquals("ws://game-session-service:8080/ws/game", properties.upstreamUrl());
    assertEquals(40, properties.reconnectAttempts());
    assertEquals(250L, properties.reconnectDelayMs());
    assertEquals(256, properties.bufferCapacity());
  }

  @Test
  void customValuesArePreserved() {
    GameplayWebSocketBridgeProperties properties =
        new GameplayWebSocketBridgeProperties("ws://example/ws/game", 3, 75L, 128);

    assertEquals("ws://example/ws/game", properties.upstreamUrl());
    assertEquals(3, properties.reconnectAttempts());
    assertEquals(75L, properties.reconnectDelayMs());
    assertEquals(128, properties.bufferCapacity());
  }
}
