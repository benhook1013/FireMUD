package net.firedevops.firemud.common.account.sourceintake;

/** Account client for the distinct selected-owner World closure HELD read. */
public interface SelectedOwnerIntakeWorldClosureAuthorizationReadClient {
  SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence readHeld(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request request);
}
