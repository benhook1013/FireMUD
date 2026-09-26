package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;

public class PublishGateFailureException extends RuntimeException {
  private final PublishGateFailureCode failureCode;
  private final String participantFailureCode;

  public PublishGateFailureException(PublishGateFailureCode failureCode, String message) {
    this(failureCode, message, null);
  }

  public PublishGateFailureException(
      PublishGateFailureCode failureCode, String message, String participantFailureCode) {
    super(message);
    this.failureCode = failureCode;
    this.participantFailureCode = participantFailureCode;
  }

  public PublishGateFailureCode failureCode() {
    return failureCode;
  }

  /**
   * Returns the owner-preserved failure code when this is a participant-unavailable result.
   *
   * <p>The publish gate has to retain this bounded bit of remote evidence because {@link
   * PublishGateFailureCode#PARTICIPANT_UNAVAILABLE} also represents permanent participant responses
   * such as an unsupported scope. Callers must still classify the value before retrying.
   */
  public String participantFailureCode() {
    return participantFailureCode;
  }
}
