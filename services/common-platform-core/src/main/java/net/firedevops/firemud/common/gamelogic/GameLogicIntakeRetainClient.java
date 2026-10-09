package net.firedevops.firemud.common.gamelogic;

/** Transport boundary for exact Game Logic selected-source retention. */
public interface GameLogicIntakeRetainClient {
  GameLogicIntakeRetainEvidence retain(GameLogicIntakeRetainEvidence.Request request);
}
