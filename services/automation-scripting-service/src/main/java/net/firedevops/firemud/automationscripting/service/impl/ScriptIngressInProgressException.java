package net.firedevops.firemud.automationscripting.service.impl;

/** Signals that an identical ingress claim is still being finalized by another request. */
final class ScriptIngressInProgressException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  ScriptIngressInProgressException() {
    super("ingress_in_progress");
  }

  ScriptIngressInProgressException(String reason) {
    super(reason);
  }
}
