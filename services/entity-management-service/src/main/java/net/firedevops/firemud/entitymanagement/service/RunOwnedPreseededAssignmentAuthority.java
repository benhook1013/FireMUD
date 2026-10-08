package net.firedevops.firemud.entitymanagement.service;

/**
 * Verifies the exact dedicated run-owned assignment action and target-bound grant.
 *
 * <p>The production adapter validates the TLS-authenticated caller and a separately provisioned
 * run-owned read-only capability, then binds the dedicated action to the stable assignment UUID,
 * Account UUID, caller-echoed expected target, owner-resolved target, and complete core payload.
 * The expected target is a correlation/precondition only; it does not authorize its own values.
 * Run/project labels, fixture operation IDs, initial-bind authority, and numeric defaults are not
 * grants. The adapter remains explicitly disabled by default.
 */
public interface RunOwnedPreseededAssignmentAuthority {
  enum Action {
    PRESEEDED_ACTOR_ASSIGNMENT
  }

  /**
   * Authenticates the caller and dedicated action before any owner reads or replay lookup. A
   * same-assignment grant mismatch must fail as an intent conflict without disclosing stored data.
   * The grant must bind the full request, including its expected target and payload.
   */
  void requireAuthorized(PreseededActorAssignmentRequest request, Action dedicatedAction);

  /**
   * Rechecks the same authenticated grant against the current complete owner target and payload. A
   * mismatch with either the owner target or caller-echoed expected target must fail as an intent
   * conflict without returning an actor identity or stored outcome.
   */
  void requireTargetBoundAuthorized(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence exactTarget,
      Action dedicatedAction);
}
