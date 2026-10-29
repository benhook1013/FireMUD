package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayTenantGameplayTagRepository.Assignment;

/** Builds the one tenant-slot issuer barrier key from persisted tenant placement evidence. */
final class CanonicalGameplayIssuerAdmissionBarrierKey {
  private CanonicalGameplayIssuerAdmissionBarrierKey() {}

  static String forTarget(String issuerId, Assignment assignment) {
    Objects.requireNonNull(assignment, "assignment");
    return forTarget(
        issuerId,
        assignment.tenantId(),
        assignment.playableStateNamespaceId(),
        assignment.tenantGameplayTag());
  }

  static String forTarget(String issuerId, UUID tenantId, UUID namespaceId, String tag) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(namespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(tag, "tenantGameplayTag");
    if (!net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.ISSUER.equals(
            issuerId)
        || !tag.matches("g[1-9][0-9]{0,9}-[0-9a-f]{32}")) {
      throw new IllegalArgumentException(
          "Issuer barrier key requires exact Account issuer placement");
    }
    return "session:game:issuer-admission:{"
        + tag
        + "}:"
        + issuerId
        + ":"
        + tenantId
        + ":"
        + namespaceId;
  }
}
