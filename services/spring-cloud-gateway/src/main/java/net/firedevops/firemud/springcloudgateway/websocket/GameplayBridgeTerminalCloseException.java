package net.firedevops.firemud.springcloudgateway.websocket;

final class GameplayBridgeTerminalCloseException extends RuntimeException {
  private final GameplayWebSocketObservability.CloseClassification closeClassification;

  GameplayBridgeTerminalCloseException(
      GameplayWebSocketObservability.CloseClassification closeClassification) {
    this.closeClassification = closeClassification;
  }

  GameplayWebSocketObservability.CloseClassification closeClassification() {
    return closeClassification;
  }
}
