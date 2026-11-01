package net.firedevops.firemud.gamesession.service;

public class AdmissionPointerConflictException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public AdmissionPointerConflictException(String message) {
    super(message);
  }

  public AdmissionPointerConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
