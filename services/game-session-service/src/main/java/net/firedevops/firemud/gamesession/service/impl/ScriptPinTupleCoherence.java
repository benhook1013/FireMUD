package net.firedevops.firemud.gamesession.service.impl;

final class ScriptPinTupleCoherence {
  private ScriptPinTupleCoherence() {}

  static void requireCoherent(String patchVersion, Long pinEpoch, String requestId) {
    boolean hasPatch = patchVersion != null && !patchVersion.isBlank();
    boolean hasEpoch = pinEpoch != null && pinEpoch > 0L;
    boolean hasRequest = requestId != null && !requestId.isBlank();
    if (!((hasPatch && hasEpoch && hasRequest) || (!hasPatch && !hasEpoch && !hasRequest))) {
      throw new IllegalArgumentException(
          "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");
    }
  }
}
