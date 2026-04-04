package net.firedevops.firemud.springcloudgateway.websocket;

import org.springframework.web.reactive.socket.CloseStatus;

final class GameplayBridgeTerminalCloseException extends RuntimeException {
  private final CloseStatus status;

  GameplayBridgeTerminalCloseException(CloseStatus status) {
    this.status = status;
  }

  CloseStatus status() {
    return status;
  }
}
