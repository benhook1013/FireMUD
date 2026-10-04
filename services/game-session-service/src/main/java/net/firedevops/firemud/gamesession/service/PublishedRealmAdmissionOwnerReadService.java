package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindAttemptRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only composition of immutable published policy, current pointer, audit, and bind evidence.
 */
@Service
@Lazy
public final class PublishedRealmAdmissionOwnerReadService {
  private static final String PUBLISHED_SOURCE = "V14_PUBLISHED";
  private static final String INITIAL_BIND_ACTOR = "game-session-initial-admission-bind";
  private static final String INITIAL_BIND_REASON = "initial admission pointer bind";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final InitialAdmissionBindCatalogRepository catalogRepository;
  private final GameplayAdmissionPointerRepository pointerRepository;
  private final GameplayAdmissionPointerEventRepository pointerEventRepository;
  private final InitialAdmissionBindAttemptRepository attemptRepository;
  private final InitialAdmissionBindOwnerProofReader ownerProofReader;

  public PublishedRealmAdmissionOwnerReadService(
      InitialAdmissionBindCatalogRepository catalogRepository,
      GameplayAdmissionPointerRepository pointerRepository,
      GameplayAdmissionPointerEventRepository pointerEventRepository,
      InitialAdmissionBindAttemptRepository attemptRepository,
      InitialAdmissionBindOwnerProofReader ownerProofReader) {
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.pointerRepository = Objects.requireNonNull(pointerRepository, "pointerRepository");
    this.pointerEventRepository =
        Objects.requireNonNull(pointerEventRepository, "pointerEventRepository");
    this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
    this.ownerProofReader = Objects.requireNonNull(ownerProofReader, "ownerProofReader");
  }

  /**
   * Reads exact published and owner-local evidence. The accepted source is deliberately limited to
   * a currently unchanged version-1 pointer: this first-bind proof does not authorize a later,
   * unproved pointer cutover.
   */
  public PublishedRealmAdmissionOwnerReadProof read(
      PublishedRealmAdmissionOwnerReadRequest request) {
    Objects.requireNonNull(request, "request");
    PublishedRealmCatalogSnapshot snapshot =
        catalogRepository
            .findPublishedSnapshot(
                request.targetNamespace(),
                request.gameSessionTenantId(),
                request.expectedCatalogRevision())
            .orElseThrow(() -> unresolved("PUBLISHED_REALM_ADMISSION_SNAPSHOT_MISSING"));
    requireSnapshotMatchesRequest(snapshot, request);

    PublishedRealmCatalogEntry entry =
        snapshot.requireVisibleEntryForAdmission(request.worldSlug(), request.realmSlug());
    requireSupportedPolicy(entry);

    GameplayAdmissionPointer pointer =
        pointerRepository
            .findByTenantIdAndWorldSlugAndRealmSlug(
                request.gameSessionTenantId(), request.worldSlug(), request.realmSlug())
            .orElseThrow(() -> unresolved("PUBLISHED_REALM_ADMISSION_POINTER_MISSING"));
    requireCurrentPointerMatches(pointer, snapshot, entry, request);

    GameplayAdmissionPointerEvent audit =
        pointerEventRepository
            .findLatestByTenantIdAndWorldSlugAndRealmSlug(
                request.gameSessionTenantId(), request.worldSlug(), request.realmSlug())
            .orElseThrow(() -> unresolved("PUBLISHED_REALM_ADMISSION_POINTER_AUDIT_MISSING"));
    requireLatestAuditMatches(audit, pointer, snapshot, entry, request);

    InitialAdmissionBindAttempt attempt =
        attemptRepository
            .findByTenantAndRequestId(
                request.gameSessionTenantId(), audit.getControlPlaneRequestId())
            .orElseThrow(() -> unresolved("PUBLISHED_REALM_ADMISSION_BIND_ATTEMPT_MISSING"));
    requirePublishedCommittedAttempt(attempt, snapshot, entry, pointer, audit, request);

    InitialAdmissionBindHoldBinding binding = binding(attempt);
    InitialAdmissionBindOwnerProof ownerProof = ownerProofReader.read(binding);
    requireOwnerProofMatches(ownerProof, attempt, audit, request);

    return new PublishedRealmAdmissionOwnerReadProof(
        snapshot.tenantId(),
        snapshot.canonicalTenantId(),
        snapshot.sourceGameRowId(),
        snapshot.sourceGameTenantKey(),
        snapshot.tenantIdentityProvenanceKind(),
        snapshot.policySetEvidence(),
        entry.policyEvidence(),
        snapshot.catalogRevision(),
        entry.realmId(),
        entry.requirePlayableStateNamespaceId(),
        entry.policyEvidence().policy().stateScope(),
        pointer.getPointerVersion(),
        pointer.getGameInstanceId(),
        attempt.versionId(),
        attempt.activeLifecycleEpoch(),
        attempt.gameTemplateId(),
        attempt.launchDescriptorId(),
        attempt.releaseBundleId(),
        attempt.publishedReleaseBundleRef(),
        attempt.versionStateEpoch(),
        attempt.initialAdmissionRequestId(),
        attempt.requestDigest(),
        attempt.attemptId(),
        audit.getId());
  }

  private static void requireSnapshotMatchesRequest(
      PublishedRealmCatalogSnapshot snapshot, PublishedRealmAdmissionOwnerReadRequest request) {
    if (snapshot.tenantId() != request.gameSessionTenantId()
        || !request.targetNamespace().equals(snapshot.targetNamespace())
        || !request.canonicalTenantId().equals(snapshot.canonicalTenantId())
        || snapshot.catalogRevision() != request.expectedCatalogRevision()
        || !request.canonicalTenantId().equals(snapshot.policySetEvidence().canonicalTenantId())
        || !snapshot.policySetEvidence().hasValidDigest(OBJECT_MAPPER)) {
      throw unresolved("PUBLISHED_REALM_ADMISSION_SNAPSHOT_MISMATCH");
    }
  }

  private static void requireSupportedPolicy(PublishedRealmCatalogEntry entry) {
    RealmEntryPolicy policy = entry.policyEvidence().policy();
    if (entry.namespaceResolution() != NamespaceResolution.RESOLVED
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY) {
      throw unresolved("PUBLISHED_REALM_ADMISSION_POLICY_UNSUPPORTED");
    }
  }

  private static void requireCurrentPointerMatches(
      GameplayAdmissionPointer pointer,
      PublishedRealmCatalogSnapshot snapshot,
      PublishedRealmCatalogEntry entry,
      PublishedRealmAdmissionOwnerReadRequest request) {
    RealmEntryPolicy policy = entry.policyEvidence().policy();
    if (pointer.getId() == null
        || !Objects.equals(pointer.getTenantId(), request.gameSessionTenantId())
        || pointer.getGameInstanceId() == null
        || pointer.getGameInstanceId() <= 0
        || !request.worldSlug().equals(pointer.getWorldSlug())
        || !request.realmSlug().equals(pointer.getRealmSlug())
        || !entry.realmId().equals(pointer.getRealmId())
        || !entry.requirePlayableStateNamespaceId().equals(pointer.getPlayableStateNamespaceId())
        || !Objects.equals(pointer.getCatalogRevision(), request.expectedCatalogRevision())
        || !Objects.equals(pointer.getPointerVersion(), request.expectedPointerVersion())
        || !Objects.equals(pointer.getPointerVersion(), 1L)
        || !pointer.isVisible()
        || !pointer.isPublicProductionRealm()
        || !Objects.equals(pointer.getStateScope(), policy.stateScope().name())
        || !Objects.equals(pointer.getCharacterCreationPolicy(), policy.entryPolicy().name())
        || !INITIAL_BIND_ACTOR.equals(pointer.getLastUpdatedBy())
        || !INITIAL_BIND_REASON.equals(pointer.getLastUpdateReason())
        || snapshot.catalogRevision() != pointer.getCatalogRevision()) {
      throw unresolved("PUBLISHED_REALM_ADMISSION_POINTER_STALE_OR_MISMATCHED");
    }
  }

  private static void requireLatestAuditMatches(
      GameplayAdmissionPointerEvent audit,
      GameplayAdmissionPointer pointer,
      PublishedRealmCatalogSnapshot snapshot,
      PublishedRealmCatalogEntry entry,
      PublishedRealmAdmissionOwnerReadRequest request) {
    RealmEntryPolicy policy = entry.policyEvidence().policy();
    if (audit.getId() == null
        || !Objects.equals(audit.getTenantId(), request.gameSessionTenantId())
        || !request.worldSlug().equals(audit.getWorldSlug())
        || !request.realmSlug().equals(audit.getRealmSlug())
        || !Objects.equals(audit.getWorldDisplayName(), policy.worldDisplayName())
        || !Objects.equals(audit.getRealmDisplayName(), policy.realmDisplayName())
        || !Objects.equals(audit.getGameInstanceId(), pointer.getGameInstanceId())
        || !Objects.equals(audit.getPointerVersion(), pointer.getPointerVersion())
        || !Objects.equals(audit.getCatalogRevision(), snapshot.catalogRevision())
        || !Objects.equals(audit.getRealmId(), entry.realmId())
        || !Objects.equals(
            audit.getPlayableStateNamespaceId(), entry.requirePlayableStateNamespaceId())
        || !audit.isVisible()
        || !audit.isPublicProductionRealm()
        || !Objects.equals(audit.getStateScope(), policy.stateScope().name())
        || !Objects.equals(audit.getCharacterCreationPolicy(), policy.entryPolicy().name())
        || audit.isRequiresCharacterSelection() != pointer.isRequiresCharacterSelection()
        || !INITIAL_BIND_ACTOR.equals(audit.getActorPrincipal())
        || !INITIAL_BIND_REASON.equals(audit.getReason())
        || !isCanonicalUuid(audit.getControlPlaneRequestId())
        || audit.getOccurredAt() == null) {
      throw unresolved("PUBLISHED_REALM_ADMISSION_POINTER_AUDIT_MISMATCH");
    }
  }

  private static void requirePublishedCommittedAttempt(
      InitialAdmissionBindAttempt attempt,
      PublishedRealmCatalogSnapshot snapshot,
      PublishedRealmCatalogEntry entry,
      GameplayAdmissionPointer pointer,
      GameplayAdmissionPointerEvent audit,
      PublishedRealmAdmissionOwnerReadRequest request) {
    if (attempt.attemptId() == null
        || attempt.status() != Status.COMMITTED
        || !PUBLISHED_SOURCE.equals(attempt.catalogSourceKind())
        || !request.targetNamespace().equals(attempt.publishedTargetNamespace())
        || !request.canonicalTenantId().equals(attempt.canonicalTenantId())
        || attempt.tenantId() != request.gameSessionTenantId()
        || !audit.getControlPlaneRequestId().equals(attempt.initialAdmissionRequestId())
        || attempt.requestDigest() == null
        || !attempt.requestDigest().matches("[0-9a-f]{64}")
        || !entry.realmId().equals(attempt.realmId())
        || !entry.requirePlayableStateNamespaceId().equals(attempt.playableStateNamespaceId())
        || !"SHARED".equals(attempt.playableStateScope())
        || !attempt.expectedNoPriorPointer()
        || attempt.catalogRevision() != request.expectedCatalogRevision()
        || attempt.versionId() != snapshot.policySetEvidence().versionId()
        || attempt.gameInstanceId() != pointer.getGameInstanceId()
        || attempt.activeLifecycleEpoch() <= 0
        || attempt.gameTemplateId() == null
        || attempt.gameTemplateId() <= 0
        || attempt.launchDescriptorId() == null
        || attempt.launchDescriptorId().isBlank()
        || attempt.releaseBundleId() == null
        || attempt.releaseBundleId() <= 0
        || attempt.publishedReleaseBundleRef() == null
        || attempt.publishedReleaseBundleRef().isBlank()
        || attempt.versionStateEpoch() == null
        || attempt.versionStateEpoch() <= 0
        || attempt.holdId() == null
        || attempt.holdFence() == null
        || attempt.pointerId() == null
        || !attempt.pointerId().equals(pointer.getId())
        || attempt.auditEventId() == null
        || !attempt.auditEventId().equals(audit.getId())
        || attempt.terminalAt() == null) {
      throw unresolved("PUBLISHED_REALM_ADMISSION_BIND_ATTEMPT_MISMATCH");
    }
  }

  private static InitialAdmissionBindHoldBinding binding(InitialAdmissionBindAttempt attempt) {
    return new InitialAdmissionBindHoldBinding(
        attempt.holdId().toString(),
        attempt.holdFence().toString(),
        attempt.tenantId(),
        attempt.realmId().toString(),
        attempt.playableStateNamespaceId().toString(),
        attempt.playableStateScope(),
        attempt.gameInstanceId(),
        attempt.versionId(),
        attempt.activeLifecycleEpoch(),
        attempt.initialAdmissionRequestId(),
        attempt.requestDigest(),
        attempt.expectedNoPriorPointer(),
        attempt.catalogRevision());
  }

  private static void requireOwnerProofMatches(
      InitialAdmissionBindOwnerProof proof,
      InitialAdmissionBindAttempt attempt,
      GameplayAdmissionPointerEvent audit,
      PublishedRealmAdmissionOwnerReadRequest request) {
    if (proof == null
        || proof.outcome() != InitialAdmissionBindOwnerProof.Outcome.COMMITTED
        || !Objects.equals(proof.holdId(), attempt.holdId().toString())
        || !Objects.equals(proof.holdFence(), attempt.holdFence().toString())
        || proof.tenantId() != request.gameSessionTenantId()
        || !Objects.equals(proof.realmUuid(), attempt.realmId().toString())
        || !Objects.equals(
            proof.playableStateNamespaceUuid(), attempt.playableStateNamespaceId().toString())
        || !Objects.equals(proof.playableStateScope(), "SHARED")
        || proof.gameInstanceId() != attempt.gameInstanceId()
        || proof.versionId() != attempt.versionId()
        || proof.activeLifecycleEpoch() != attempt.activeLifecycleEpoch()
        || !Objects.equals(proof.initialAdmissionRequestId(), attempt.initialAdmissionRequestId())
        || !Objects.equals(proof.requestDigest(), attempt.requestDigest())
        || !Objects.equals(proof.ownerProofId(), attempt.attemptId().toString())
        || !proof.expectedNoPriorPointer()
        || proof.expectedCatalogRevision() != request.expectedCatalogRevision()
        || proof.pointerVersion() != request.expectedPointerVersion()
        || !Objects.equals(proof.pointerAuditId(), audit.getId().toString())
        || !Objects.equals(proof.pointerAuditRequestDigest(), attempt.requestDigest())
        || proof.futureCommitPrevented()
        || proof.ownerProofId() == null
        || proof.ownerProofId().isBlank()) {
      throw unresolved("PUBLISHED_REALM_ADMISSION_OWNER_PROOF_MISSING_OR_MISMATCHED");
    }
  }

  private static IllegalStateException unresolved(String code) {
    return new IllegalStateException(
        code + ": authoritative published realm admission evidence is unavailable");
  }

  private static boolean isCanonicalUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      return value.equals(UUID.fromString(value).toString());
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
