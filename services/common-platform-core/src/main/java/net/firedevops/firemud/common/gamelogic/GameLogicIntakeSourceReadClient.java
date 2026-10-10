package net.firedevops.firemud.common.gamelogic;

/** Dedicated authenticated Account owner readback. */
public interface GameLogicIntakeSourceReadClient {
  GameLogicIntakeSourceReadEvidence read(GameLogicIntakeSourceReadEvidence.Request request);
}
