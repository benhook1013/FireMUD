package net.firedevops.firemud.springcloudgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.gateway.gameplay.bridge")
public record GameplayWebSocketBridgeProperties(
    String upstreamUrl, int reconnectAttempts, long reconnectDelayMs, int bufferCapacity) {

  public GameplayWebSocketBridgeProperties {
    upstreamUrl =
        upstreamUrl == null || upstreamUrl.isBlank()
            ? "ws://game-session-service:8080/ws/game"
            : upstreamUrl;
    reconnectAttempts = reconnectAttempts <= 0 ? 160 : reconnectAttempts;
    reconnectDelayMs = reconnectDelayMs <= 0 ? 250L : reconnectDelayMs;
    bufferCapacity = bufferCapacity <= 0 ? 256 : bufferCapacity;
  }
}
