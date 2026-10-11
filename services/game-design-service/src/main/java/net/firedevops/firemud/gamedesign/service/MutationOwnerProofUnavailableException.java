package net.firedevops.firemud.gamedesign.service;

/** A safe refusal for a mutation whose required owner proof is unavailable. */
public class MutationOwnerProofUnavailableException extends IllegalStateException {
  private final String errorCode;

  public MutationOwnerProofUnavailableException(String errorCode, String safeMessage) {
    super(errorCode + ": " + safeMessage);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
