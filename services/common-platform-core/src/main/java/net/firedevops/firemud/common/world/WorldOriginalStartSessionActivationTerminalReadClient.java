package net.firedevops.firemud.common.world;

/** Reads one exact immutable original activation terminal from the authenticated World owner. */
public interface WorldOriginalStartSessionActivationTerminalReadClient {
  /**
   * Returns the exact first World receipt; unresolved or unavailable reads propagate their status.
   */
  WorldOriginalStartSessionActivationTerminalEvidence read(
      WorldOriginalStartSessionActivationTerminalReadInput input);
}
