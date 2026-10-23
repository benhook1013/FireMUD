package net.firedevops.firemud.entitymanagement.service;

/**
 * Resolves current Game Session owner evidence independently of the caller's expected target.
 *
 * <p>A production implementation must bind the exact Account UUID to canonical tenant, realm,
 * active instance, namespace/scope, published PRESEEDED_ONLY policy, release/catalog version,
 * admission-pointer evidence, and exact positive {@code pointerVersion} and {@code
 * activeWorldEpoch} counters. It must reread those owners for every request; counters cannot be
 * inferred from digests, defaults, or numeric identifiers. Cached assertions and caller-selected
 * scope are not authority. Roster discovery remains non-admitting.
 */
@FunctionalInterface
public interface CanonicalGameplayRosterOwnerEvidencePort {
  CanonicalGameplayRosterOwnerEvidence resolveCurrentTarget(
      CanonicalGameplayRosterReadRequest request);

  /** Missing original operations are unavailable, never evidence that references were released. */
  default net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse
      readOriginalSelection(CanonicalGameplayRosterReadRequest request) {
    throw new OwnerEvidenceUnavailableException();
  }

  static net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse
      requireOriginalSelection(
          CanonicalGameplayRosterReadRequest request,
          net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse
              original) {
    if (original == null || !original.hasRequest()) {
      throw new OwnerEvidenceUnavailableException();
    }
    var expected =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.playerExecutionContext().sessionUuid(),
            original.getRequest().getTargetNamespace(),
            request.expectedTarget().toWire());
    var verified =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
            .requireResponse(expected, original);
    net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.requireDereference(
        verified);
    return verified;
  }

  final class OwnerEvidenceUnavailableException extends IllegalStateException {
    public OwnerEvidenceUnavailableException() {
      super("CANONICAL_GAMEPLAY_ROSTER_OWNER_EVIDENCE_UNAVAILABLE");
    }

    public OwnerEvidenceUnavailableException(Throwable cause) {
      super("CANONICAL_GAMEPLAY_ROSTER_OWNER_EVIDENCE_UNAVAILABLE", cause);
    }
  }
}
