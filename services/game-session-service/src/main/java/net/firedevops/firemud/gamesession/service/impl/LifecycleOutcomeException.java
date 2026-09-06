package net.firedevops.firemud.gamesession.service.impl;

/** Expected game-instance lifecycle rejection that belongs in the typed gRPC response. */
final class LifecycleOutcomeException extends IllegalStateException {
  private final String code;
  private final String detailMessage;

  LifecycleOutcomeException(String code, String detailMessage) {
    super(code + ": " + detailMessage);
    this.code = code;
    this.detailMessage = detailMessage;
  }

  String code() {
    return code;
  }

  String detailMessage() {
    return detailMessage;
  }
}
