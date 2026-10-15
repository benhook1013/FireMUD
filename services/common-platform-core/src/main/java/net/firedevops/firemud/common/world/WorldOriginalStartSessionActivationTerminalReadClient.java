package net.firedevops.firemud.common.world;

/** Reads one exact immutable original activation terminal from the authenticated World owner. */
public interface WorldOriginalStartSessionActivationTerminalReadClient {
  /** Returns the exact first World receipt, or {@code null} when World has no terminal receipt. */
  WorldOriginalStartSessionActivationTerminalEvidence read(
      WorldOriginalStartSessionActivationTerminalReadInput input);
}
