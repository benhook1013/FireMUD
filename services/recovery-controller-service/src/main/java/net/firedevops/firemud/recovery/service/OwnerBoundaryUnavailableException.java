package net.firedevops.firemud.recovery.service;

public class OwnerBoundaryUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public OwnerBoundaryUnavailableException(String code) {
    super(code);
  }
}
