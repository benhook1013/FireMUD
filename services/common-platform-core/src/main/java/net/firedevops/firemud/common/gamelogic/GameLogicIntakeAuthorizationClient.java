package net.firedevops.firemud.common.gamelogic;

/** Explicit producer call and same-identity recovery/abort operations. */
public interface GameLogicIntakeAuthorizationClient {
  GameLogicIntakeAuthorizationEvidence.Result authorize(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential);

  GameLogicIntakeAuthorizationEvidence.Result recover(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential);

  GameLogicIntakeAuthorizationEvidence.Result abort(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential);
}
