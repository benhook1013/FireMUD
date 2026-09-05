package net.firedevops.firemud.automationscripting.service.impl;

/** Signals that an identical ingress claim is still being finalized by another request. */
final class ScriptIngressInProgressException extends RuntimeException {
  ScriptIngressInProgressException() {
    super("ingress_in_progress");
  }
}
