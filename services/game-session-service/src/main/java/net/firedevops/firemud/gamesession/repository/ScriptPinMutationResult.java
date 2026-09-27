package net.firedevops.firemud.gamesession.repository;

/** Durable result of an idempotent Game Session script-pin mutation. */
public record ScriptPinMutationResult(
    String previousScriptPatchVersion,
    Long previousScriptPinEpoch,
    String resultingScriptPatchVersion,
    Long resultingScriptPinEpoch,
    String controlPlaneRequestId,
    String errorCode,
    Long previousScriptPatchBaseVersionId,
    Long resultingScriptPatchBaseVersionId) {

  public ScriptPinMutationResult(
      String previousScriptPatchVersion,
      Long previousScriptPinEpoch,
      String resultingScriptPatchVersion,
      Long resultingScriptPinEpoch,
      String controlPlaneRequestId,
      String errorCode) {
    this(
        previousScriptPatchVersion,
        previousScriptPinEpoch,
        resultingScriptPatchVersion,
        resultingScriptPinEpoch,
        controlPlaneRequestId,
        errorCode,
        null,
        null);
  }

  public boolean succeeded() {
    return errorCode == null || errorCode.isBlank();
  }
}
