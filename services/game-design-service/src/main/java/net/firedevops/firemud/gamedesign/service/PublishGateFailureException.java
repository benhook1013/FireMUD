package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;

public class PublishGateFailureException extends RuntimeException {
  private final PublishGateFailureCode failureCode;

  public PublishGateFailureException(PublishGateFailureCode failureCode, String message) {
    super(message);
    this.failureCode = failureCode;
  }

  public PublishGateFailureCode failureCode() {
    return failureCode;
  }
}
