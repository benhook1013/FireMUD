package net.firedevops.firemud.gamesession.service;

public class AdmissionPointerConflictException extends IllegalStateException {
  public AdmissionPointerConflictException(String message) {
    super(message);
  }

  public AdmissionPointerConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
