package net.firedevops.firemud.common.entity.sourceintake;

/** Transport boundary for reading Entity's exact original selected-source terminal. */
public interface EntitySelectedSourceIntakeTerminalReadClient {
  EntitySelectedSourceIntakeTerminalReadEvidence read(
      EntitySelectedSourceIntakeTerminalReadEvidence.Request request);
}
