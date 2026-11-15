package net.firedevops.firemud.common.account.sourceintake;

/** Account's authenticated held-retention read, separate from original Draft authorization. */
public interface SelectedOwnerIntakeAuthorizationReadClient {
  SelectedOwnerIntakeAuthorizationReadEvidence read(
      SelectedOwnerIntakeAuthorizationReadEvidence.Request request);
}
