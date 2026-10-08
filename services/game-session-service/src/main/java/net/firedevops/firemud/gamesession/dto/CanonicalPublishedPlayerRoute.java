package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/**
 * Source-only current route plus its exact selected row from Game Design's complete sealed policy
 * set. This evidence is not gameplay admission or actor-entry authorization.
 */
public record CanonicalPublishedPlayerRoute(
    CanonicalPlayableTarget route,
    PublishedRealmEntryPolicySetEvidence policySetEvidence,
    PublishedRealmEntryPolicyEvidence selectedPolicyEvidence) {
  public CanonicalPublishedPlayerRoute {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(policySetEvidence, "policySetEvidence");
    Objects.requireNonNull(selectedPolicyEvidence, "selectedPolicyEvidence");
    RealmEntryPolicy policy = selectedPolicyEvidence.policy();
    if (!policySetEvidence.policies().contains(selectedPolicyEvidence)
        || !policySetEvidence.target().canonicalTenantId().equals(route.canonicalTenantId())
        || !policySetEvidence.target().canonicalVersionId().equals(route.canonicalVersionId())
        || !policy.worldSlug().equals(route.worldSlug())
        || !policy.realmSlug().equals(route.realmSlug())
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || !"SHARED".equals(route.playableStateScope())) {
      throw new IllegalArgumentException(
          "Selected PRESEEDED_ONLY policy does not belong to the exact public route and set");
    }
  }

  public RealmEntryPolicy entryPolicy() {
    return selectedPolicyEvidence.policy();
  }
}
