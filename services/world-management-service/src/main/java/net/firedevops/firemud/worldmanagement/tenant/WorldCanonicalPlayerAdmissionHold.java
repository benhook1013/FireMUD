package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;

/**
 * Distinct World-owned player-admission lifecycle hold. This is neither a first-pointer hold nor
 * Account authorization, Game Session's binding decision, or gameplay admission permission.
 *
 * <p>The complete original lease and exact World owner evidence remain immutable. Its deadline is
 * diagnostic only. Release requires independently authenticated exact Account terminal evidence AND
 * Game Session installation/cleanup evidence; those producers do not exist in this slice.
 */
public record WorldCanonicalPlayerAdmissionHold(
    UUID holdId,
    UUID holdFence,
    Request request,
    WorldCanonicalInstanceLifecycleEvidence worldEvidence) {
  public WorldCanonicalPlayerAdmissionHold {
    requireOpaque(holdId);
    requireOpaque(holdFence);
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(worldEvidence, "worldEvidence");
    request.requireExactActiveWorld(worldEvidence);
  }

  /** Retained lease deadline triggers recovery diagnostics, never automatic release. */
  public long diagnosticExpiresAtMillis() {
    return Long.parseLong((String) request.lease().carrier().get("expiresAt"));
  }

  /**
   * Complete original Account lease plus independently expected World lifecycle tuple. Account's
   * regionEpoch is a separate domain and is never interpreted as this World lifecycle epoch.
   */
  public record Request(
      AccountGameplayAdmissionLeaseEvidence lease,
      long expectedLifecycleEpoch,
      long expectedRowVersion) {
    public Request {
      Objects.requireNonNull(lease, "lease");
      if (expectedLifecycleEpoch <= 0 || expectedRowVersion < 0) {
        throw new IllegalArgumentException("Expected World lifecycle tuple is invalid");
      }
      if (!(lease.carrier().get("bindingScope") instanceof Map<?, ?> scope)
          || !"SHARED".equals(scope.get("playableStateScope"))
          || !(lease.carrier().get("membershipBaseline") instanceof Map<?, ?> baseline)
          || !"ACTIVE".equals(baseline.get("membershipLifecycleState"))) {
        throw new IllegalArgumentException("Unsupported or non-admitting original lease scope");
      }
    }

    public UUID leaseId() {
      return UUID.fromString((String) lease.carrier().get("leaseId"));
    }

    public UUID attemptId() {
      return UUID.fromString((String) lease.carrier().get("requestId"));
    }

    public UUID canonicalGameInstanceId() {
      return UUID.fromString((String) scope().get("gameInstanceId"));
    }

    public String targetNamespace() {
      return (String) lease.carrier().get("targetNamespace");
    }

    public boolean sameBinding(Request other) {
      return other != null
          && lease.hasSameIdentity(other.lease)
          && expectedLifecycleEpoch == other.expectedLifecycleEpoch
          && expectedRowVersion == other.expectedRowVersion;
    }

    public void requireExactActiveWorld(WorldCanonicalInstanceLifecycleEvidence evidence) {
      Objects.requireNonNull(evidence, "evidence");
      requireExactWorldSelector(evidence.request());
      if (!"ACTIVE".equals(evidence.lifecycleStatus())
          || expectedLifecycleEpoch != evidence.lifecycleEpoch()
          || expectedRowVersion != evidence.rowVersion()) {
        throw new IllegalArgumentException("World target is not the exact expected ACTIVE tuple");
      }
    }

    void requireExactWorldSelector(WorldCanonicalInstanceLifecycleEvidence.Request selector) {
      if (!targetNamespace().equals(selector.targetNamespace())
          || !scope().get("tenantId").equals(selector.canonicalTenantId().toString())
          || !scope().get("worldSlug").equals(selector.worldSlug())
          || !scope().get("gameInstanceId").equals(selector.canonicalGameInstanceId().toString())
          || !scope()
              .get("playableStateNamespaceId")
              .equals(selector.playableStateNamespaceId().toString())
          || !scope().get("playableStateScope").equals(selector.playableStateScope())
          || !selector.publicProduction()) {
        throw new IllegalArgumentException("Original lease differs from canonical World scope");
      }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scope() {
      return (Map<String, Object>) lease.carrier().get("bindingScope");
    }
  }

  private static void requireOpaque(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Hold identity and fence must be opaque UUIDs");
    }
  }
}
