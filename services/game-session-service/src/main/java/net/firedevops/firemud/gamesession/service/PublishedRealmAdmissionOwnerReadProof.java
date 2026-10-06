package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/**
 * Point-in-time owner composition for one exact published PRESEEDED_ONLY realm and its first
 * admission bind. This observation is not a pointer mutation or through-commit admission gate.
 */
public record PublishedRealmAdmissionOwnerReadProof(
    long gameSessionTenantId,
    UUID canonicalTenantId,
    long sourceGameRowId,
    String sourceGameTenantKey,
    String tenantIdentityProvenanceKind,
    PublishedRealmEntryPolicySetEvidence publishedPolicySet,
    PublishedRealmEntryPolicyEvidence selectedPolicyEvidence,
    long catalogRevision,
    UUID realmId,
    UUID playableStateNamespaceId,
    RealmEntryPolicy.StateScope playableStateScope,
    long pointerVersion,
    long gameInstanceId,
    long publishedVersionId,
    long activeLifecycleEpoch,
    long gameTemplateId,
    String launchDescriptorId,
    long releaseBundleId,
    String publishedReleaseBundleRef,
    long versionStateEpoch,
    String initialAdmissionRequestId,
    String requestDigest,
    UUID initialAdmissionAttemptId,
    long pointerAuditId) {
  public PublishedRealmAdmissionOwnerReadProof {
    if (gameSessionTenantId <= 0
        || sourceGameRowId <= 0
        || catalogRevision <= 0
        || pointerVersion <= 0
        || gameInstanceId <= 0
        || publishedVersionId <= 0
        || activeLifecycleEpoch <= 0
        || gameTemplateId <= 0
        || releaseBundleId <= 0
        || versionStateEpoch <= 0
        || pointerAuditId <= 0) {
      throw new IllegalArgumentException("Published realm admission proof tuple must be positive");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(realmId, "realmId");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    requireNonNil(initialAdmissionAttemptId, "initialAdmissionAttemptId");
    requireText(sourceGameTenantKey, "sourceGameTenantKey");
    requireText(tenantIdentityProvenanceKind, "tenantIdentityProvenanceKind");
    requireText(launchDescriptorId, "launchDescriptorId");
    requireText(publishedReleaseBundleRef, "publishedReleaseBundleRef");
    requireText(initialAdmissionRequestId, "initialAdmissionRequestId");
    if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestDigest must be lowercase SHA-256 hex");
    }
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    Objects.requireNonNull(publishedPolicySet, "publishedPolicySet");
    Objects.requireNonNull(selectedPolicyEvidence, "selectedPolicyEvidence");
    if (!canonicalTenantId.equals(publishedPolicySet.canonicalTenantId())
        || publishedVersionId != publishedPolicySet.versionId()
        || !publishedPolicySet.policies().contains(selectedPolicyEvidence)
        || !canonicalTenantId.equals(selectedPolicyEvidence.canonicalTenantId())
        || sourceGameRowId != selectedPolicyEvidence.sourceGameRowId()
        || !sourceGameTenantKey.equals(selectedPolicyEvidence.sourceGameTenantKey())
        || !tenantIdentityProvenanceKind.equals(
            selectedPolicyEvidence.tenantIdentityProvenanceKind())
        || !selectedPolicyEvidence.policy().visible()
        || !selectedPolicyEvidence.policy().publicProduction()
        || playableStateScope != RealmEntryPolicy.StateScope.SHARED
        || selectedPolicyEvidence.policy().entryPolicy()
            != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || selectedPolicyEvidence.policy().stateScope() != playableStateScope) {
      throw new IllegalArgumentException("Published realm proof evidence is contradictory");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
