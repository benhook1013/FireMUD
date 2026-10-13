package net.firedevops.firemud.common.gamedesign;

import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;

/** Reads complete immutable selected-owner source content from Game Design. */
public interface SelectedOwnerIntakeSourceClient {
  SelectedOwnerIntakeSourceContent read(SelectedOwnerIntakeSourceReadEvidence.Request request);
}
