package net.firedevops.firemud.gamedesign.service;

public final class ScriptPatchPublishFailureException extends IllegalStateException {
  private final String failureCode;

  public ScriptPatchPublishFailureException(String failureCode, String message) {
    this(failureCode, message, null);
  }

  public ScriptPatchPublishFailureException(String failureCode, String message, Throwable cause) {
    super(message, cause);
    this.failureCode = failureCode;
  }

  public String failureCode() {
    return failureCode;
  }
}
