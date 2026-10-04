package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindAttemptRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation fails fast for Spring-managed collaborators without exposing a"
            + " partially initialized owner service.")
public class DatabaseInitialAdmissionBindOwnerService implements InitialAdmissionBindOwnerService {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final String STATE_SCOPE = "SHARED";
  private static final String FIXTURE_SOURCE = "V9_FIXTURE";
  private static final String PUBLISHED_SOURCE = "V14_PUBLISHED";
  private static final String PUBLISHED_CHARACTER_CREATION_POLICY = "PRESEEDED_ONLY";
  private static final String AUDIT_ACTOR = "game-session-initial-admission-bind";
  private static final String AUDIT_REASON = "initial admission pointer bind";

  private final InitialAdmissionBindCatalogRepository catalogRepository;
  private final InitialAdmissionBindAttemptRepository attemptRepository;
  private final GameplayAdmissionPointerEventRepository eventRepository;
  private final GameInstanceRepository gameInstanceRepository;

  public DatabaseInitialAdmissionBindOwnerService(
      InitialAdmissionBindCatalogRepository catalogRepository,
      InitialAdmissionBindAttemptRepository attemptRepository,
      GameplayAdmissionPointerEventRepository eventRepository,
      GameInstanceRepository gameInstanceRepository) {
    this.catalogRepository =
        Objects.requireNonNull(catalogRepository, "catalogRepository must not be null");
    this.attemptRepository =
        Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
    this.eventRepository =
        Objects.requireNonNull(eventRepository, "eventRepository must not be null");
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
  }

  /**
   * Persists the immutable visible public shared demo catalog row without creating an admission
   * pointer. The run-owned fixture coordinator must first resolve the real tenant/template through
   * Game Design; no startup configuration or external request is an authority source here.
   */
  @Override
  @Transactional
  public InitialAdmissionBindCatalog registerPublicSharedFixtureCatalog(
      InitialAdmissionBindCatalogDescriptor descriptor) {
    validateCatalogDescriptor(descriptor);
    catalogRepository.lockTenantCatalogAndSharedNamespace(descriptor.tenantId());
    Optional<InitialAdmissionBindCatalog> existing =
        catalogRepository.findByTenantId(descriptor.tenantId());
    if (existing.isPresent()) {
      requireSameCatalog(existing.get(), descriptor);
      return existing.get();
    }

    UUID sharedNamespaceId =
        catalogRepository.getOrCreateTenantSharedNamespace(descriptor.tenantId());
    return catalogRepository.insert(descriptor, UUID.randomUUID(), sharedNamespaceId);
  }

  /** Persists the attempt before any coordinator call to the separate World hold owner. */
  @Override
  @Transactional
  public InitialAdmissionBindAttempt beginIntent(InitialAdmissionBindRequest request) {
    validateRequest(request);
    InitialAdmissionBindCatalog catalog = resolveCatalog(request);
    attemptRepository.lockAttemptAndRealm(
        request.tenantId(), request.initialAdmissionRequestId(), catalog.realmId());

    Optional<InitialAdmissionBindAttempt> existing =
        attemptRepository.findByTenantAndRequestIdForUpdate(
            request.tenantId(), request.initialAdmissionRequestId());
    if (existing.isPresent()) {
      requireSameRequest(existing.get(), request, catalog);
      return existing.get();
    }

    requireTargetMatchesCatalogAndRequest(catalog, request);
    if (attemptRepository.hasPointerForCatalog(catalog)) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_ALREADY_BOUND: catalog realm already has an admission pointer");
    }
    if (attemptRepository.hasPointerForRuntimeTarget(
        request.tenantId(), request.gameInstanceId())) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_RUNTIME_ALREADY_ROUTED: runtime target already has an admission pointer");
    }
    Optional<InitialAdmissionBindAttempt> pending =
        attemptRepository.findPendingByTenantAndRealmForUpdate(
            request.tenantId(), catalog.realmId());
    if (pending.isPresent()) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_ATTEMPT_PENDING: another durable attempt owns this realm");
    }

    Instant now = Instant.now();
    return attemptRepository.insertPending(
        new InitialAdmissionBindAttempt(
            UUID.randomUUID(),
            request.tenantId(),
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            true,
            catalog.catalogRevision(),
            request.gameInstanceId(),
            request.versionId(),
            request.activeLifecycleEpoch(),
            null,
            null,
            Status.PENDING,
            null,
            null,
            now,
            now,
            null,
            request.isPublishedCatalogRequest() ? PUBLISHED_SOURCE : FIXTURE_SOURCE,
            request.publishedCatalog() == null
                ? null
                : request.publishedCatalog().targetNamespace(),
            request.publishedCatalog() == null
                ? null
                : request.publishedCatalog().canonicalTenantId(),
            request.launchEvidence() == null ? null : request.launchEvidence().gameTemplateId(),
            request.launchEvidence() == null ? null : request.launchEvidence().launchDescriptorId(),
            request.launchEvidence() == null ? null : request.launchEvidence().releaseBundleId(),
            request.launchEvidence() == null
                ? null
                : request.launchEvidence().publishedReleaseBundleRef(),
            request.launchEvidence() == null
                ? null
                : request.launchEvidence().versionStateEpoch()));
  }

  @Override
  @Transactional
  public InitialAdmissionBindAttempt attachHold(InitialAdmissionBindHoldBinding binding) {
    validateBinding(binding);
    UUID realmId = UUID.fromString(binding.realmUuid());
    attemptRepository.lockAttemptAndRealm(
        binding.tenantId(), binding.initialAdmissionRequestId(), realmId);
    InitialAdmissionBindAttempt attempt =
        requireAttemptForUpdate(binding.tenantId(), binding.initialAdmissionRequestId());
    requireSameBinding(attempt, binding);
    return attachHoldIfNeeded(attempt, binding, Instant.now());
  }

  @Override
  @Transactional
  public InitialAdmissionBindOwnerProof commit(InitialAdmissionBindHoldBinding binding) {
    validateBinding(binding);
    UUID realmId = UUID.fromString(binding.realmUuid());
    attemptRepository.lockAttemptAndRealm(
        binding.tenantId(), binding.initialAdmissionRequestId(), realmId);
    InitialAdmissionBindAttempt attempt =
        requireAttemptForUpdate(binding.tenantId(), binding.initialAdmissionRequestId());
    requireSameBinding(attempt, binding);
    requireAttachedHold(attempt, binding);

    if (attempt.status() != Status.PENDING) {
      return read(binding);
    }

    InitialAdmissionBindCatalog catalog;
    try {
      catalog = requireCatalogForAttempt(attempt);
    } catch (IllegalStateException exception) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    requireTargetMatchesCatalogAndAttempt(catalog, attempt);
    if (attemptRepository.hasPointerForCatalog(catalog)
        || attemptRepository.hasPointerForRuntimeTarget(
            attempt.tenantId(), attempt.gameInstanceId())) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_NO_PRIOR_POINTER_FAILED: a pointer exists before initial bind");
    }

    Instant now = Instant.now();
    GameplayAdmissionPointer pointer =
        attemptRepository.insertPointer(catalog, attempt.gameInstanceId(), now);
    GameplayAdmissionPointerEvent auditEvent = new GameplayAdmissionPointerEvent();
    auditEvent.setWorldSlug(pointer.getWorldSlug());
    auditEvent.setRealmSlug(pointer.getRealmSlug());
    auditEvent.setWorldDisplayName(pointer.getWorldDisplayName());
    auditEvent.setRealmDisplayName(pointer.getRealmDisplayName());
    auditEvent.setTenantId(pointer.getTenantId());
    auditEvent.setGameInstanceId(pointer.getGameInstanceId());
    auditEvent.setPointerVersion(pointer.getPointerVersion());
    auditEvent.setCatalogRevision(pointer.getCatalogRevision());
    auditEvent.setRealmId(pointer.getRealmId());
    auditEvent.setPlayableStateNamespaceId(pointer.getPlayableStateNamespaceId());
    auditEvent.setVisible(pointer.isVisible());
    auditEvent.setPublicProductionRealm(pointer.isPublicProductionRealm());
    auditEvent.setRequiresCharacterSelection(pointer.isRequiresCharacterSelection());
    auditEvent.setStateScope(pointer.getStateScope());
    auditEvent.setCharacterCreationPolicy(pointer.getCharacterCreationPolicy());
    auditEvent.setActorPrincipal(AUDIT_ACTOR);
    auditEvent.setReason(AUDIT_REASON);
    auditEvent.setControlPlaneRequestId(attempt.initialAdmissionRequestId());
    auditEvent.setOccurredAt(now);
    GameplayAdmissionPointerEvent savedAuditEvent = eventRepository.save(auditEvent);

    attemptRepository.markCommitted(attempt, pointer.getId(), savedAuditEvent.getId(), now);
    InitialAdmissionBindOwnerProof proof = read(binding);
    if (proof.outcome() != Outcome.COMMITTED) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_COMMIT_READBACK_INVALID: pointer, audit, and ledger did not agree");
    }
    return proof;
  }

  @Override
  @Transactional
  public InitialAdmissionBindOwnerProof abort(InitialAdmissionBindHoldBinding binding) {
    validateBinding(binding);
    UUID realmId = UUID.fromString(binding.realmUuid());
    attemptRepository.lockAttemptAndRealm(
        binding.tenantId(), binding.initialAdmissionRequestId(), realmId);
    InitialAdmissionBindAttempt attempt =
        requireAttemptForUpdate(binding.tenantId(), binding.initialAdmissionRequestId());
    requireSameBinding(attempt, binding);

    if (attempt.status() == Status.COMMITTED) {
      return read(binding);
    }
    if (attempt.status() == Status.ABORTED) {
      requireAttachedHold(attempt, binding);
      return read(binding);
    }

    attempt = attachHoldIfNeeded(attempt, binding, Instant.now());
    attemptRepository.markAborted(attempt, Instant.now());
    InitialAdmissionBindOwnerProof proof = read(binding);
    if (proof.outcome() != Outcome.ABORTED || !proof.futureCommitPrevented()) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_ABORT_READBACK_INVALID: durable abort tombstone was not proven");
    }
    return proof;
  }

  @Override
  @Transactional(readOnly = true)
  public InitialAdmissionBindOwnerProof read(InitialAdmissionBindHoldBinding binding) {
    if (!isValidBinding(binding)) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    Optional<InitialAdmissionBindAttempt> maybeAttempt =
        attemptRepository.findByTenantAndRequestId(
            binding.tenantId(), binding.initialAdmissionRequestId());
    if (maybeAttempt.isEmpty()) {
      return proof(binding, Outcome.NOT_FOUND, null, null, 0L, null, false);
    }
    InitialAdmissionBindAttempt attempt = maybeAttempt.get();
    if (!sameBindingTuple(attempt, binding)) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    if (attempt.holdId() == null || attempt.holdFence() == null) {
      if (attempt.status() == Status.PENDING) {
        return proof(
            binding, Outcome.PENDING, attempt.attemptId().toString(), null, 0L, null, false);
      }
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    if (!attempt.holdId().toString().equals(binding.holdId())
        || !attempt.holdFence().toString().equals(binding.holdFence())) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }

    return switch (attempt.status()) {
      case PENDING ->
          proof(binding, Outcome.PENDING, attempt.attemptId().toString(), null, 0L, null, false);
      case COMMITTED -> readCommittedProof(attempt, binding);
      case ABORTED -> readAbortedProof(attempt, binding);
    };
  }

  private InitialAdmissionBindOwnerProof readCommittedProof(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindHoldBinding binding) {
    if (attempt.pointerId() == null
        || attempt.auditEventId() == null
        || attempt.terminalAt() == null) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    InitialAdmissionBindCatalog catalog;
    try {
      catalog = requireCatalogForAttempt(attempt);
    } catch (IllegalStateException exception) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    Optional<GameplayAdmissionPointer> maybePointer =
        attemptRepository.findPointerById(attempt.pointerId());
    if (maybePointer.isEmpty()) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    GameplayAdmissionPointer pointer = maybePointer.get();
    Optional<GameplayAdmissionPointerEvent> maybeAudit =
        eventRepository
            .findByTenantIdAndWorldSlugAndRealmSlugOrderByIdDesc(
                attempt.tenantId(), catalog.worldSlug(), catalog.realmSlug())
            .stream()
            .filter(event -> Objects.equals(event.getId(), attempt.auditEventId()))
            .findFirst();
    if (maybeAudit.isEmpty()
        || !matchesCommittedEvidence(attempt, catalog, pointer, maybeAudit.get())) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    GameplayAdmissionPointerEvent audit = maybeAudit.get();
    return proof(
        binding,
        Outcome.COMMITTED,
        attempt.attemptId().toString(),
        Long.toString(audit.getId()),
        audit.getPointerVersion(),
        attempt.requestDigest(),
        false);
  }

  private InitialAdmissionBindOwnerProof readAbortedProof(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindHoldBinding binding) {
    if (attempt.pointerId() != null
        || attempt.auditEventId() != null
        || attempt.terminalAt() == null) {
      return proof(binding, Outcome.ERROR, null, null, 0L, null, false);
    }
    return proof(binding, Outcome.ABORTED, attempt.attemptId().toString(), null, 0L, null, true);
  }

  private boolean matchesCommittedEvidence(
      InitialAdmissionBindAttempt attempt,
      InitialAdmissionBindCatalog catalog,
      GameplayAdmissionPointer pointer,
      GameplayAdmissionPointerEvent audit) {
    return pointer.getId().equals(attempt.pointerId())
        && Objects.equals(pointer.getTenantId(), attempt.tenantId())
        && Objects.equals(pointer.getWorldSlug(), catalog.worldSlug())
        && Objects.equals(pointer.getRealmSlug(), catalog.realmSlug())
        && Objects.equals(pointer.getRealmId(), attempt.realmId())
        && Objects.equals(pointer.getPlayableStateNamespaceId(), attempt.playableStateNamespaceId())
        && pointer.isVisible()
        && pointer.isPublicProductionRealm()
        && pointer.isRequiresCharacterSelection() == catalog.requiresCharacterSelection()
        && Objects.equals(pointer.getStateScope(), catalog.stateScope())
        && Objects.equals(pointer.getCharacterCreationPolicy(), catalog.characterCreationPolicy())
        && pointer.getPointerVersion() != null
        && pointer.getPointerVersion() >= 1L
        && Objects.equals(audit.getId(), attempt.auditEventId())
        && Objects.equals(audit.getTenantId(), attempt.tenantId())
        && Objects.equals(audit.getWorldSlug(), catalog.worldSlug())
        && Objects.equals(audit.getRealmSlug(), catalog.realmSlug())
        && Objects.equals(audit.getWorldDisplayName(), catalog.worldDisplayName())
        && Objects.equals(audit.getRealmDisplayName(), catalog.realmDisplayName())
        && Objects.equals(audit.getGameInstanceId(), attempt.gameInstanceId())
        && Objects.equals(audit.getPointerVersion(), 1L)
        && Objects.equals(audit.getCatalogRevision(), attempt.catalogRevision())
        && Objects.equals(audit.getRealmId(), attempt.realmId())
        && Objects.equals(audit.getPlayableStateNamespaceId(), attempt.playableStateNamespaceId())
        && audit.isVisible()
        && audit.isPublicProductionRealm()
        && audit.isRequiresCharacterSelection() == catalog.requiresCharacterSelection()
        && Objects.equals(audit.getStateScope(), catalog.stateScope())
        && Objects.equals(audit.getCharacterCreationPolicy(), catalog.characterCreationPolicy())
        && Objects.equals(audit.getControlPlaneRequestId(), attempt.initialAdmissionRequestId())
        && Objects.equals(audit.getActorPrincipal(), AUDIT_ACTOR)
        && Objects.equals(audit.getReason(), AUDIT_REASON)
        && audit.getOccurredAt() != null;
  }

  private InitialAdmissionBindCatalog requireCatalogForAttempt(
      InitialAdmissionBindAttempt attempt) {
    if (PUBLISHED_SOURCE.equals(attempt.catalogSourceKind())) {
      PublishedRealmCatalogSnapshot snapshot =
          catalogRepository
              .findPublishedSnapshot(
                  attempt.publishedTargetNamespace(), attempt.tenantId(), attempt.catalogRevision())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "INITIAL_ADMISSION_PUBLISHED_SNAPSHOT_MISSING: durable attempt lost its immutable snapshot"));
      if (!snapshot.canonicalTenantId().equals(attempt.canonicalTenantId())
          || snapshot.policySetEvidence().versionId() != attempt.versionId()) {
        throw new IllegalStateException(
            "INITIAL_ADMISSION_PUBLISHED_SNAPSHOT_MISMATCH: attempt no longer matches its exact published snapshot");
      }
      PublishedRealmCatalogEntry entry =
          snapshot.entries().stream()
              .filter(candidate -> candidate.realmId().equals(attempt.realmId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "INITIAL_ADMISSION_PUBLISHED_ENTRY_MISSING: durable attempt lost its selected entry"));
      InitialAdmissionBindCatalog catalog =
          requirePublishedCatalog(
              snapshot,
              entry,
              attempt.gameTemplateId() == null ? 0L : attempt.gameTemplateId(),
              entry.policyEvidence().policy().worldSlug(),
              entry.policyEvidence().policy().realmSlug());
      if (!entry.playableStateNamespaceId().equals(attempt.playableStateNamespaceId())
          || !catalog.stateScope().equals(attempt.playableStateScope())
          || !PUBLISHED_SOURCE.equals(attempt.catalogSourceKind())) {
        throw new IllegalStateException(
            "INITIAL_ADMISSION_PUBLISHED_ENTRY_MISMATCH: attempt no longer matches its exact published entry");
      }
      return catalog;
    }
    if (!FIXTURE_SOURCE.equals(attempt.catalogSourceKind())) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_CATALOG_SOURCE_INVALID: durable attempt has an unknown source kind");
    }
    InitialAdmissionBindCatalog catalog =
        catalogRepository
            .findByTenantId(attempt.tenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "INITIAL_ADMISSION_CATALOG_MISSING: durable attempt lost its owner catalog"));
    if (!catalog.realmId().equals(attempt.realmId())
        || !catalog.playableStateNamespaceId().equals(attempt.playableStateNamespaceId())
        || catalog.catalogRevision() != attempt.catalogRevision()
        || !catalog.stateScope().equals(attempt.playableStateScope())) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_CATALOG_MISMATCH: attempt no longer matches its owner catalog");
    }
    return catalog;
  }

  private InitialAdmissionBindCatalog resolveCatalog(InitialAdmissionBindRequest request) {
    if (!request.isPublishedCatalogRequest()) {
      if (request.publishedCatalog() != null || request.launchEvidence() != null) {
        throw new IllegalArgumentException(
            "INVALID_ARGUMENT: published catalog and launch evidence must be supplied together");
      }
      return catalogRepository
          .findByTenantIdAndSelectors(request.tenantId(), request.worldSlug(), request.realmSlug())
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "INITIAL_ADMISSION_CATALOG_NOT_FOUND: owner fixture catalog row is missing"));
    }
    InitialAdmissionBindRequest.PublishedCatalogBinding reference = request.publishedCatalog();
    InitialAdmissionBindRequest.LaunchEvidence launch = request.launchEvidence();
    if (reference.targetNamespace() == null
        || reference.targetNamespace().isBlank()
        || reference.canonicalTenantId() == null
        || reference.catalogRevision() <= 0
        || launch.gameTemplateId() <= 0
        || launch.releaseBundleId() <= 0
        || launch.versionStateEpoch() <= 0
        || launch.launchDescriptorId() == null
        || launch.launchDescriptorId().isBlank()
        || launch.publishedReleaseBundleRef() == null
        || launch.publishedReleaseBundleRef().isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: published admission requires exact snapshot and authored launch evidence");
    }
    PublishedRealmCatalogSnapshot snapshot =
        catalogRepository
            .findPublishedSnapshot(
                reference.targetNamespace(), request.tenantId(), reference.catalogRevision())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "INITIAL_ADMISSION_PUBLISHED_SNAPSHOT_NOT_FOUND: exact immutable snapshot is absent"));
    if (!reference.canonicalTenantId().equals(snapshot.canonicalTenantId())
        || snapshot.policySetEvidence().versionId() != request.versionId()) {
      throw new IllegalArgumentException(
          "INITIAL_ADMISSION_PUBLISHED_SNAPSHOT_MISMATCH: local tenant, canonical tenant, revision, or version changed");
    }
    PublishedRealmCatalogEntry selected =
        snapshot.requireVisibleEntryForAdmission(request.worldSlug(), request.realmSlug());
    return requirePublishedCatalog(
        snapshot, selected, launch.gameTemplateId(), request.worldSlug(), request.realmSlug());
  }

  private InitialAdmissionBindCatalog requirePublishedCatalog(
      PublishedRealmCatalogSnapshot snapshot,
      PublishedRealmCatalogEntry entry,
      long gameTemplateId,
      String worldSlug,
      String realmSlug) {
    RealmEntryPolicy policy = entry.policyEvidence().policy();
    if (gameTemplateId <= 0
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || !policy.worldSlug().equals(worldSlug)
        || !policy.realmSlug().equals(realmSlug)
        || entry.namespaceResolution() != PublishedRealmCatalogEntry.NamespaceResolution.RESOLVED) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_PUBLISHED_POLICY_UNSUPPORTED: only visible public SHARED PRESEEDED_ONLY entries with resolved namespaces are admissible");
    }
    return new InitialAdmissionBindCatalog(
        entry.realmId(),
        snapshot.tenantId(),
        gameTemplateId,
        policy.worldSlug(),
        policy.worldDisplayName(),
        policy.realmSlug(),
        policy.realmDisplayName(),
        snapshot.catalogRevision(),
        entry.requirePlayableStateNamespaceId(),
        true,
        true,
        true,
        STATE_SCOPE,
        PUBLISHED_CHARACTER_CREATION_POLICY,
        snapshot.createdAt());
  }

  private InitialAdmissionBindAttempt attachHoldIfNeeded(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindHoldBinding binding, Instant now) {
    if (attempt.holdId() != null || attempt.holdFence() != null) {
      requireAttachedHold(attempt, binding);
      return attempt;
    }
    return attemptRepository.attachHold(
        attempt, UUID.fromString(binding.holdId()), UUID.fromString(binding.holdFence()), now);
  }

  private void requireAttachedHold(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindHoldBinding binding) {
    if (attempt.holdId() == null
        || attempt.holdFence() == null
        || !attempt.holdId().toString().equals(binding.holdId())
        || !attempt.holdFence().toString().equals(binding.holdFence())) {
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: initial admission hold identity changed or was not attached");
    }
  }

  private InitialAdmissionBindAttempt requireAttemptForUpdate(long tenantId, String requestId) {
    return attemptRepository
        .findByTenantAndRequestIdForUpdate(tenantId, requestId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "INITIAL_ADMISSION_INTENT_NOT_FOUND: durable GS intent is required first"));
  }

  private void requireSameCatalog(
      InitialAdmissionBindCatalog catalog, InitialAdmissionBindCatalogDescriptor descriptor) {
    if (catalog.tenantId() != descriptor.tenantId()
        || catalog.gameTemplateId() != descriptor.gameTemplateId()
        || !catalog.worldSlug().equals(descriptor.worldSlug())
        || !catalog.worldDisplayName().equals(descriptor.worldDisplayName())
        || !catalog.realmSlug().equals(descriptor.realmSlug())
        || !catalog.realmDisplayName().equals(descriptor.realmDisplayName())
        || catalog.requiresCharacterSelection() != descriptor.requiresCharacterSelection()) {
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: initial admission catalog metadata changed for tenant");
    }
  }

  private void requireSameRequest(
      InitialAdmissionBindAttempt attempt,
      InitialAdmissionBindRequest request,
      InitialAdmissionBindCatalog catalog) {
    if (attempt.tenantId() != request.tenantId()
        || !attempt.initialAdmissionRequestId().equals(request.initialAdmissionRequestId())
        || !attempt.requestDigest().equals(request.requestDigest())
        || !matchesCatalogSource(attempt, request)
        || !attempt.realmId().equals(catalog.realmId())
        || !attempt.playableStateNamespaceId().equals(catalog.playableStateNamespaceId())
        || !attempt.playableStateScope().equals(catalog.stateScope())
        || !attempt.expectedNoPriorPointer()
        || attempt.catalogRevision() != catalog.catalogRevision()
        || attempt.gameInstanceId() != request.gameInstanceId()
        || attempt.versionId() != request.versionId()
        || attempt.activeLifecycleEpoch() != request.activeLifecycleEpoch()) {
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: initial admission request identity changed");
    }
  }

  private boolean matchesCatalogSource(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindRequest request) {
    if (request.isPublishedCatalogRequest()) {
      var reference = request.publishedCatalog();
      var launch = request.launchEvidence();
      return PUBLISHED_SOURCE.equals(attempt.catalogSourceKind())
          && Objects.equals(attempt.publishedTargetNamespace(), reference.targetNamespace())
          && Objects.equals(attempt.canonicalTenantId(), reference.canonicalTenantId())
          && Objects.equals(attempt.gameTemplateId(), launch.gameTemplateId())
          && Objects.equals(attempt.launchDescriptorId(), launch.launchDescriptorId())
          && Objects.equals(attempt.releaseBundleId(), launch.releaseBundleId())
          && Objects.equals(attempt.publishedReleaseBundleRef(), launch.publishedReleaseBundleRef())
          && Objects.equals(attempt.versionStateEpoch(), launch.versionStateEpoch());
    }
    return FIXTURE_SOURCE.equals(attempt.catalogSourceKind())
        && attempt.publishedTargetNamespace() == null
        && attempt.canonicalTenantId() == null
        && attempt.gameTemplateId() == null
        && attempt.launchDescriptorId() == null
        && attempt.releaseBundleId() == null
        && attempt.publishedReleaseBundleRef() == null
        && attempt.versionStateEpoch() == null;
  }

  private void requireSameBinding(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindHoldBinding binding) {
    if (!sameBindingTuple(attempt, binding)) {
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: initial admission hold tuple does not match durable intent");
    }
  }

  private boolean sameBindingTuple(
      InitialAdmissionBindAttempt attempt, InitialAdmissionBindHoldBinding binding) {
    return attempt.tenantId() == binding.tenantId()
        && attempt.realmId().toString().equals(binding.realmUuid())
        && attempt
            .playableStateNamespaceId()
            .toString()
            .equals(binding.playableStateNamespaceUuid())
        && attempt.playableStateScope().equals(binding.playableStateScope())
        && attempt.gameInstanceId() == binding.gameInstanceId()
        && attempt.versionId() == binding.versionId()
        && attempt.activeLifecycleEpoch() == binding.activeLifecycleEpoch()
        && attempt.initialAdmissionRequestId().equals(binding.initialAdmissionRequestId())
        && attempt.requestDigest().equals(binding.requestDigest())
        && attempt.expectedNoPriorPointer() == binding.expectedNoPriorPointer()
        && attempt.catalogRevision() == binding.expectedCatalogRevision();
  }

  private void requireTargetMatchesCatalogAndRequest(
      InitialAdmissionBindCatalog catalog, InitialAdmissionBindRequest request) {
    GameInstance gameInstance =
        gameInstanceRepository
            .findByTenantIdAndGameInstanceIdForUpdate(request.tenantId(), request.gameInstanceId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "GAME_INSTANCE_NOT_FOUND: initial admission target is not GS-owned"));
    if (!Objects.equals(gameInstance.getGameTemplateId(), catalog.gameTemplateId())
        || !Objects.equals(gameInstance.getVersionId(), request.versionId())) {
      throw new IllegalArgumentException(
          "INITIAL_ADMISSION_TARGET_MISMATCH: target does not match persisted tenant/template/version");
    }
    if (request.isPublishedCatalogRequest()) {
      InitialAdmissionBindRequest.LaunchEvidence launch = request.launchEvidence();
      if (!Objects.equals(gameInstance.getLaunchDescriptorId(), launch.launchDescriptorId())
          || !Objects.equals(gameInstance.getReleaseBundleId(), launch.releaseBundleId())
          || !Objects.equals(gameInstance.getVersionStateEpoch(), launch.versionStateEpoch())
          || !Objects.equals(
              gameInstance.getRunOwnedStartPublishedReleaseBundleRef(),
              launch.publishedReleaseBundleRef())) {
        throw new IllegalArgumentException(
            "INITIAL_ADMISSION_LAUNCH_EVIDENCE_MISMATCH: authored descriptor no longer matches the persisted runtime target");
      }
    }
  }

  private void requireTargetMatchesCatalogAndAttempt(
      InitialAdmissionBindCatalog catalog, InitialAdmissionBindAttempt attempt) {
    GameInstance gameInstance =
        gameInstanceRepository
            .findByTenantIdAndGameInstanceIdForUpdate(attempt.tenantId(), attempt.gameInstanceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "GAME_INSTANCE_NOT_FOUND: initial admission target is not GS-owned"));
    if (!Objects.equals(gameInstance.getGameTemplateId(), catalog.gameTemplateId())
        || !Objects.equals(gameInstance.getVersionId(), attempt.versionId())) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_TARGET_MISMATCH: target does not match persisted tenant/template/version");
    }
    if (PUBLISHED_SOURCE.equals(attempt.catalogSourceKind())
        && (!Objects.equals(gameInstance.getLaunchDescriptorId(), attempt.launchDescriptorId())
            || !Objects.equals(gameInstance.getReleaseBundleId(), attempt.releaseBundleId())
            || !Objects.equals(gameInstance.getVersionStateEpoch(), attempt.versionStateEpoch())
            || !Objects.equals(
                gameInstance.getRunOwnedStartPublishedReleaseBundleRef(),
                attempt.publishedReleaseBundleRef()))) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_LAUNCH_EVIDENCE_MISMATCH: authored descriptor no longer matches the persisted runtime target");
    }
  }

  private void validateCatalogDescriptor(InitialAdmissionBindCatalogDescriptor descriptor) {
    if (descriptor == null || descriptor.tenantId() <= 0L || descriptor.gameTemplateId() <= 0L) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: public shared fixture catalog requires positive tenant and template IDs");
    }
    requireSlug(descriptor.worldSlug(), "worldSlug");
    requireText(descriptor.worldDisplayName(), "worldDisplayName", 200);
    requireSlug(descriptor.realmSlug(), "realmSlug");
    requireText(descriptor.realmDisplayName(), "realmDisplayName", 200);
  }

  private void validateRequest(InitialAdmissionBindRequest request) {
    if (request == null
        || request.tenantId() <= 0L
        || request.gameInstanceId() <= 0L
        || request.versionId() <= 0L
        || request.activeLifecycleEpoch() <= 0L) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: initial admission request requires positive tenant, target, version, and lifecycle epoch");
    }
    requireText(request.initialAdmissionRequestId(), "initialAdmissionRequestId", 128);
    if (request.requestDigest() == null || !SHA_256.matcher(request.requestDigest()).matches()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: requestDigest must be lowercase SHA-256 hex");
    }
    requireSlug(request.worldSlug(), "worldSlug");
    requireSlug(request.realmSlug(), "realmSlug");
  }

  private void validateBinding(InitialAdmissionBindHoldBinding binding) {
    if (!isValidBinding(binding)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: initial admission hold binding is incomplete or unsupported");
    }
  }

  private boolean isValidBinding(InitialAdmissionBindHoldBinding binding) {
    if (binding == null
        || binding.tenantId() <= 0L
        || binding.gameInstanceId() <= 0L
        || binding.versionId() <= 0L
        || binding.activeLifecycleEpoch() <= 0L
        || binding.expectedCatalogRevision() <= 0L
        || !binding.expectedNoPriorPointer()
        || !STATE_SCOPE.equals(binding.playableStateScope())
        || binding.requestDigest() == null
        || !SHA_256.matcher(binding.requestDigest()).matches()) {
      return false;
    }
    try {
      requireText(binding.initialAdmissionRequestId(), "initialAdmissionRequestId", 128);
      return isCanonicalUuid(binding.holdId())
          && isCanonicalUuid(binding.holdFence())
          && isCanonicalUuid(binding.realmUuid())
          && isCanonicalUuid(binding.playableStateNamespaceUuid());
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private boolean isCanonicalUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private InitialAdmissionBindOwnerProof proof(
      InitialAdmissionBindHoldBinding binding,
      Outcome outcome,
      String ownerProofId,
      String pointerAuditId,
      long pointerVersion,
      String pointerAuditRequestDigest,
      boolean futureCommitPrevented) {
    if (binding == null) {
      return new InitialAdmissionBindOwnerProof(
          outcome,
          null,
          null,
          0L,
          null,
          null,
          null,
          0L,
          0L,
          0L,
          null,
          null,
          false,
          0L,
          ownerProofId,
          pointerAuditId,
          pointerVersion,
          pointerAuditRequestDigest,
          futureCommitPrevented);
    }
    return new InitialAdmissionBindOwnerProof(
        outcome,
        binding.holdId(),
        binding.holdFence(),
        binding.tenantId(),
        binding.realmUuid(),
        binding.playableStateNamespaceUuid(),
        binding.playableStateScope(),
        binding.gameInstanceId(),
        binding.versionId(),
        binding.activeLifecycleEpoch(),
        binding.initialAdmissionRequestId(),
        binding.requestDigest(),
        binding.expectedNoPriorPointer(),
        binding.expectedCatalogRevision(),
        ownerProofId,
        pointerAuditId,
        pointerVersion,
        pointerAuditRequestDigest,
        futureCommitPrevented);
  }

  private static void requireSlug(String value, String name) {
    if (value == null || value.length() > 120 || !SLUG.matcher(value).matches()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: " + name + " is not a canonical slug");
    }
  }

  private static void requireText(String value, String name, int maxLength) {
    if (value == null
        || value.isBlank()
        || value.length() > maxLength
        || !value.equals(value.trim())
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: " + name + " is invalid");
    }
  }
}
