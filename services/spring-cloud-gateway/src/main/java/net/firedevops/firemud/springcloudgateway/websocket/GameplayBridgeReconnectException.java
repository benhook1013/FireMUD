package net.firedevops.firemud.springcloudgateway.websocket;

import org.springframework.web.reactive.socket.CloseStatus;

final class GameplayBridgeReconnectException extends RuntimeException {
  private final CloseStatus status;

  GameplayBridgeReconnectException(CloseStatus status) {
    this.status = status;
  }

  CloseStatus status() {
    return status;
  }
}
