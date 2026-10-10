package net.firedevops.firemud.common.account.sourceintake;

/** Dedicated Account confirmation of a preliminary selected-owner source-read scope. */
public interface SelectedOwnerIntakeSourceReadClient {
  SelectedOwnerIntakeSourceReadEvidence read(SelectedOwnerIntakeSourceReadEvidence.Request request);
}
