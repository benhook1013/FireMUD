package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalClosedAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.ExpectedClosedEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.PreparedExpectedClosed;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable Game Session owner transaction and exact readback for one canonical first-OPEN bind. */
public class CanonicalInitialAdmissionRepository {
  private static final String COMMITTED = "COMMITTED";
  private static final String PENDING = "PENDING";
  private static final String ABORTED = "ABORTED";
  private static final String AUDIT_ACTOR = "game-session-canonical-initial-admission";
  private static final String AUDIT_REASON = "World-held initial admission";
  private static final String COMMIT_PROOF_DOMAIN =
      "gs-canonical-initial-admission-commit-proof/v1";
  private static final String ABORT_PROOF_DOMAIN = "gs-canonical-initial-admission-abort-proof/v1";
  private static final String OPERATION_TABLE = "game_session_canonical_initial_admission_attempt";

  private final DSLContext dsl;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final GameSessionCanonicalAdmissionPointerRepository pointerRepository;
  private final CanonicalGameInstanceLaunchAssociationRepository launchRepository;
  private final GameplayAdmissionPointerEventRepository eventRepository;

  /** One exact current pointer row plus its validated owner result and GS-owned snapshot digest. */
  public record CurrentOpenSnapshot(
      CanonicalInitialAdmissionOwnerProof ownerProof, String admissionPointerSnapshotDigest) {
    private static final java.util.regex.Pattern SNAPSHOT_DIGEST =
        java.util.regex.Pattern.compile("[0-9a-f]{64}");

    public CurrentOpenSnapshot {
      Objects.requireNonNull(ownerProof, "ownerProof");
      if (!ownerProof.provesCommit()
          || admissionPointerSnapshotDigest == null
          || !SNAPSHOT_DIGEST.matcher(admissionPointerSnapshotDigest).matches()) {
        throw new IllegalArgumentException(
            "Current OPEN snapshot requires committed owner proof and raw SHA-256 digest");
      }
    }
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected owner collaborators are validated before use; construction acquires no resources.")
  public CanonicalInitialAdmissionRepository(
      DSLContext dsl,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      GameplayAdmissionPointerEventRepository eventRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.pointerRepository = Objects.requireNonNull(pointerRepository, "pointerRepository");
    this.launchRepository = Objects.requireNonNull(launchRepository, "launchRepository");
    this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
  }

  /** Persists the immutable request identity before remote World verification is attempted. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void reserve(CanonicalInitialAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    requireWritableReadCommittedOwnerTransaction();
    lockTarget(request);
    Attempt existing =
        findAttempt(request.targetNamespace(), request.initialAdmissionRequestId(), true);
    if (existing != null && !PENDING.equals(existing.status())) {
      requireSameRequestIdentity(existing, request);
      return;
    }
    if (existing == null
        && request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      lockAllAdmissionRepresentationsForNoPrior();
    }
    CanonicalRealmCatalogSnapshot catalog = lockCatalog(request);
    CanonicalInitialAdmissionLaunchTarget launchTarget =
        lockLaunchTarget(request, existing == null);
    requireRequestMatchesCatalogAndLaunch(request, catalog, launchTarget);

    if (existing != null) {
      requireSameRequest(existing, request, launchTarget);
      return;
    }
    Attempt otherPending = findPendingForRealm(request, true);
    if (otherPending != null) {
      throw new CanonicalInitialAdmissionConflictException(
          "Another initial-admission request already owns the pending realm attempt");
    }

    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      pointerRepository.requireNoAdmissionPointerRepresentations(
          request.targetNamespace(),
          request.canonicalTenantId(),
          request.realmId(),
          request.worldSlug(),
          catalog.realmSlug(),
          launchTarget.association().gameSessionTenantId(),
          launchTarget.gameInstanceId());
    } else {
      pointerRepository.lockExpectedClosedForRealm(
          request.targetNamespace(),
          request.canonicalTenantId(),
          request.realmId(),
          request.expectedPriorPointerVersion(),
          request.expectedCatalogRevision());
    }

    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATION_TABLE
                + " (target_namespace, initial_admission_request_id, request_digest, "
                + "canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id, "
                + "playable_state_scope, canonical_game_instance_id, game_session_tenant_id, "
                + "game_instance_id, canonical_version_id, runtime_version_id, "
                + "expected_catalog_revision, origin_kind, "
                + "expected_prior_pointer_version, active_lifecycle_epoch, hold_id, hold_fence, "
                + "hold_binding_digest, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', "
                + "CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ)) ON CONFLICT DO NOTHING",
            request.targetNamespace(),
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            launchTarget.association().gameSessionTenantId(),
            launchTarget.gameInstanceId(),
            request.canonicalVersionId(),
            launchTarget.runtimeVersionId(),
            request.expectedCatalogRevision(),
            request.originKind().name(),
            request.expectedPriorPointerVersion(),
            request.activeLifecycleEpoch(),
            request.holdId(),
            request.holdFence(),
            request.holdBindingDigest(),
            utc(now),
            utc(now));
    Attempt reserved =
        findAttempt(request.targetNamespace(), request.initialAdmissionRequestId(), true);
    if (reserved == null) {
      throw new CanonicalInitialAdmissionConflictException(
          "Initial-admission request reservation conflicted with durable owner state");
    }
    requireSameRequest(reserved, request, launchTarget);
    if (inserted == 0 && !PENDING.equals(reserved.status())) {
      throw new CanonicalInitialAdmissionConflictException(
          "Initial-admission request identity was concurrently terminalized");
    }
  }

  /**
   * Commits the first pointer, append-only audit and terminal owner result in one short
   * transaction.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void commit(
      CanonicalInitialAdmissionRequest request,
      CanonicalInitialAdmissionWorldProof worldProof,
      PreparedExpectedClosed preparedExpectedClosed) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(worldProof, "worldProof");
    worldProof.requireMatches(request);
    requireWritableReadCommittedOwnerTransaction();
    lockTarget(request);
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      lockAllAdmissionRepresentationsForNoPrior();
    }
    CanonicalRealmCatalogSnapshot catalog = lockCatalog(request);
    Attempt attempt = requireAttemptForUpdate(request);
    CanonicalInitialAdmissionLaunchTarget launchTarget = lockLaunchTarget(request, true);
    requireSameRequest(attempt, request, launchTarget);
    requireRequestMatchesCatalogAndLaunch(request, catalog, launchTarget);
    if (!PENDING.equals(attempt.status())) {
      return;
    }

    ExpectedClosedEvidence closedOrigin = lockExpectedClosedOrigin(request, preparedExpectedClosed);
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      pointerRepository.requireNoAdmissionPointerRepresentations(
          request.targetNamespace(),
          request.canonicalTenantId(),
          request.realmId(),
          request.worldSlug(),
          catalog.realmSlug(),
          launchTarget.association().gameSessionTenantId(),
          launchTarget.gameInstanceId());
    }

    long pointerVersion =
        request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
            ? 1L
            : Math.addExact(request.expectedPriorPointerVersion(), 1L);
    Instant committedAt = nextTimestamp(attempt.updatedAt());
    long pointerId = writeOpenPointer(request, catalog, launchTarget, pointerVersion, committedAt);
    long auditEventId =
        eventRepository.appendCanonicalInitialAdmissionOpen(
            request, catalog, launchTarget, pointerVersion, catalog.catalogRevision(), committedAt);
    String proofDigest =
        commitProofDigest(
            attempt,
            pointerVersion,
            auditEventId,
            committedAt,
            closedOrigin == null ? null : closedOrigin.originalRequestId(),
            closedOrigin == null ? null : closedOrigin.requestDigest(),
            closedOrigin == null ? null : closedOrigin.auditEventId());
    int updated =
        dsl.execute(
            "UPDATE "
                + OPERATION_TABLE
                + " SET status = 'COMMITTED', pointer_id = ?, pointer_version = ?, "
                + "audit_event_id = ?, prior_pointer_request_id = ?, "
                + "prior_pointer_request_digest = ?, prior_pointer_audit_event_id = ?, "
                + "commit_proof_digest = ?, updated_at = CAST(? AS TIMESTAMPTZ), "
                + "terminal_at = CAST(? AS TIMESTAMPTZ) "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ? AND status = 'PENDING'",
            pointerId,
            pointerVersion,
            auditEventId,
            closedOrigin == null ? null : closedOrigin.originalRequestId(),
            closedOrigin == null ? null : closedOrigin.requestDigest(),
            closedOrigin == null ? null : closedOrigin.auditEventId(),
            proofDigest,
            utc(committedAt),
            utc(committedAt),
            request.targetNamespace(),
            request.initialAdmissionRequestId());
    if (updated != 1) {
      throw new CanonicalInitialAdmissionConflictException(
          "Initial-admission attempt was concurrently terminalized before pointer commit");
    }
  }

  /**
   * Records a terminal local abort only while the exact expected prior authority is still current.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void abort(
      CanonicalInitialAdmissionRequest request,
      PreparedExpectedClosed preparedExpectedClosed,
      String reason) {
    Objects.requireNonNull(request, "request");
    requireText(reason, "reason", 500);
    requireWritableReadCommittedOwnerTransaction();
    lockTarget(request);
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      lockAllAdmissionRepresentationsForNoPrior();
    }
    CanonicalRealmCatalogSnapshot catalog = lockCatalog(request);
    Attempt attempt = requireAttemptForUpdate(request);
    CanonicalInitialAdmissionLaunchTarget launchTarget = lockLaunchTarget(request, false);
    requireSameRequest(attempt, request, launchTarget);
    requireRequestMatchesCatalogAndLaunch(request, catalog, launchTarget);
    if (!PENDING.equals(attempt.status())) {
      return;
    }

    ExpectedClosedEvidence closedOrigin = lockExpectedClosedOrigin(request, preparedExpectedClosed);
    if (closedOrigin == null) {
      pointerRepository.requireNoAdmissionPointerRepresentations(
          request.targetNamespace(),
          request.canonicalTenantId(),
          request.realmId(),
          request.worldSlug(),
          catalog.realmSlug(),
          launchTarget.association().gameSessionTenantId(),
          launchTarget.gameInstanceId());
    }
    Instant terminalAt = nextTimestamp(attempt.updatedAt());
    String abortProofDigest =
        abortProofDigest(
            attempt,
            reason,
            terminalAt,
            closedOrigin == null ? null : closedOrigin.originalRequestId(),
            closedOrigin == null ? null : closedOrigin.requestDigest(),
            closedOrigin == null ? null : closedOrigin.auditEventId());
    int updated =
        dsl.execute(
            "UPDATE "
                + OPERATION_TABLE
                + " SET status = 'ABORTED', prior_pointer_request_id = ?, "
                + "prior_pointer_request_digest = ?, prior_pointer_audit_event_id = ?, "
                + "abort_proof_digest = ?, abort_reason = ?, updated_at = CAST(? AS TIMESTAMPTZ), "
                + "terminal_at = CAST(? AS TIMESTAMPTZ) "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ? AND status = 'PENDING'",
            closedOrigin == null ? null : closedOrigin.originalRequestId(),
            closedOrigin == null ? null : closedOrigin.requestDigest(),
            closedOrigin == null ? null : closedOrigin.auditEventId(),
            abortProofDigest,
            reason,
            utc(terminalAt),
            utc(terminalAt),
            request.targetNamespace(),
            request.initialAdmissionRequestId());
    if (updated != 1) {
      throw new CanonicalInitialAdmissionConflictException(
          "Initial-admission attempt was concurrently terminalized before abort");
    }
  }

  /** Reads the exact durable result only after the owner transaction has committed. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalInitialAdmissionOwnerProof> read(
      String targetNamespace, String initialAdmissionRequestId) {
    requireOutsideTransaction();
    Record row = findAttemptRecord(targetNamespace, initialAdmissionRequestId, false);
    if (row == null) {
      return Optional.empty();
    }
    Attempt attempt = toAttempt(row);
    if (PENDING.equals(attempt.status())) {
      return Optional.of(toProof(attempt, CanonicalInitialAdmissionOwnerProof.Outcome.PENDING));
    }
    if (COMMITTED.equals(attempt.status())) {
      verifyCommittedReadback(attempt);
      return Optional.of(toProof(attempt, CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));
    }
    if (ABORTED.equals(attempt.status())) {
      verifyAbortReadback(attempt);
      return Optional.of(toProof(attempt, CanonicalInitialAdmissionOwnerProof.Outcome.ABORTED));
    }
    throw new CanonicalInitialAdmissionReconciliationRequiredException(
        "Persisted initial-admission attempt has an unsupported owner outcome");
  }

  /**
   * Reads the current canonical OPEN pointer and its exact committed initial-admission proof. This
   * is a point-in-time owner read, not an authorization held through a later mutation.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public CanonicalInitialAdmissionOwnerProof readCurrentOpenForRealm(
      String targetNamespace, UUID canonicalTenantUuid, UUID realmUuid) {
    return readCurrentOpenSnapshotForRealm(targetNamespace, canonicalTenantUuid, realmUuid)
        .ownerProof();
  }

  /**
   * Reads the exact current canonical OPEN pointer and its committed proof as one owner snapshot.
   * The digest commits to the validated current pointer projection, not the owner's commit-proof
   * digest or a caller-provided echo.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public CurrentOpenSnapshot readCurrentOpenSnapshotForRealm(
      String targetNamespace, UUID canonicalTenantUuid, UUID realmUuid) {
    requireOutsideTransaction();
    requireCurrentOpenReadSelector(targetNamespace, canonicalTenantUuid, realmUuid);

    CurrentOpenPointer before =
        readCurrentOpenPointer(targetNamespace, canonicalTenantUuid, realmUuid);
    before.requireCanonicalOpen(targetNamespace, canonicalTenantUuid, realmUuid);
    String requestId = before.initialAdmissionRequestId();
    if (requestId == null || requestId.isBlank()) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Current canonical OPEN pointer has no initial-admission request identity");
    }

    CanonicalInitialAdmissionOwnerProof proof =
        read(targetNamespace, requestId)
            .orElseThrow(
                () ->
                    new CanonicalInitialAdmissionReconciliationRequiredException(
                        "Current canonical OPEN pointer has no durable owner attempt"));
    if (!proof.provesCommit() || !before.matches(proof)) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Current canonical OPEN pointer does not match its exact committed owner proof");
    }

    CurrentOpenPointer after =
        readCurrentOpenPointer(targetNamespace, canonicalTenantUuid, realmUuid);
    if (!before.equals(after)) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Current canonical OPEN pointer changed during owner readback");
    }
    return new CurrentOpenSnapshot(proof, snapshotDigestOrReconciliation(before, proof));
  }

  /** Package-private same-package test seam for malformed persisted projection evidence. */
  @SuppressFBWarnings(
      value = "DCN_NULLPOINTER_EXCEPTION",
      justification =
          "Canonical projection validation rejects missing persisted fields with NullPointerException;"
              + " this narrow readback boundary translates that rejection into required reconciliation.")
  static String snapshotDigestOrReconciliation(
      CurrentOpenPointer pointer, CanonicalInitialAdmissionOwnerProof proof) {
    try {
      return pointer.snapshotDigest(proof);
    } catch (IllegalArgumentException | NullPointerException malformed) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Current canonical OPEN snapshot contains malformed persisted projection evidence",
          malformed);
    }
  }

  private CurrentOpenPointer readCurrentOpenPointer(
      String targetNamespace, UUID canonicalTenantId, UUID realmId) {
    List<Record> rows =
        dsl.fetch(
            "SELECT * FROM gameplay_admission_pointer "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? "
                + "AND representation_version = 3 AND admission_state = 'OPEN'",
            targetNamespace,
            canonicalTenantId,
            realmId);
    if (rows.size() != 1) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Canonical realm must have exactly one current representation-version-3 OPEN pointer");
    }
    return CurrentOpenPointer.from(rows.get(0));
  }

  private static void requireCurrentOpenReadSelector(
      String targetNamespace, UUID canonicalTenantId, UUID realmId) {
    if (targetNamespace == null || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace is not a canonical owner selector");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(realmId, "realmId");
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private ExpectedClosedEvidence lockExpectedClosedOrigin(
      CanonicalInitialAdmissionRequest request, PreparedExpectedClosed prepared) {
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      if (prepared != null) {
        throw new CanonicalInitialAdmissionConflictException(
            "NO_PRIOR_POINTER cannot carry prepared CLOSED-origin evidence");
      }
      return null;
    }
    if (prepared == null) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "EXPECT_CLOSED requires prepared original CLOSED owner evidence");
    }
    ExpectedClosedEvidence evidence = pointerRepository.lockExpectedClosed(prepared);
    if (!evidence.targetNamespace().equals(request.targetNamespace())
        || !evidence.canonicalTenantId().equals(request.canonicalTenantId())
        || !evidence.realmId().equals(request.realmId())
        || evidence.pointerVersion() != request.expectedPriorPointerVersion()
        || evidence.catalogRevision() != request.expectedCatalogRevision()) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Locked original CLOSED authority does not match the tagged request origin");
    }
    return evidence;
  }

  private long writeOpenPointer(
      CanonicalInitialAdmissionRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launchTarget,
      long pointerVersion,
      Instant updatedAt) {
    CanonicalGameInstanceLaunchAssociation association = launchTarget.association();
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      Record inserted =
          dsl.fetchOne(
              "INSERT INTO gameplay_admission_pointer ("
                  + "world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                  + "game_instance_id, pointer_version, visible, requires_character_selection, "
                  + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
                  + "created_at, updated_at, public_production_realm, catalog_revision, realm_id, "
                  + "playable_state_namespace_id, representation_version, target_namespace, "
                  + "canonical_tenant_id, admission_state, canonical_game_instance_id, "
                  + "canonical_version_id, runtime_version_id, initial_admission_request_id, "
                  + "initial_admission_request_digest, initial_admission_origin_kind, "
                  + "initial_admission_prior_pointer_version, initial_admission_active_epoch, "
                  + "initial_admission_hold_id, initial_admission_hold_fence, "
                  + "initial_admission_hold_binding_digest) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 'game-session-canonical-initial-admission', "
                  + "'World-held initial admission', ?, ?, ?, ?, ?, ?, 3, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                  + "RETURNING id",
              catalog.worldSlug(),
              catalog.realmSlug(),
              catalog.sourceIntakeReceipt().source().worldDisplayName(),
              catalog.realmDisplayName(),
              association.gameSessionTenantId(),
              launchTarget.gameInstanceId(),
              pointerVersion,
              catalog.visible(),
              catalog.stateScope(),
              catalog.characterCreationPolicy(),
              toLocalDateTime(updatedAt),
              toLocalDateTime(updatedAt),
              catalog.publicProduction(),
              catalog.catalogRevision(),
              catalog.realmId(),
              catalog.playableStateNamespaceId(),
              request.targetNamespace(),
              request.canonicalTenantId(),
              request.canonicalGameInstanceId(),
              request.canonicalVersionId(),
              launchTarget.runtimeVersionId(),
              request.initialAdmissionRequestId(),
              request.requestDigest(),
              request.originKind().name(),
              request.expectedPriorPointerVersion(),
              request.activeLifecycleEpoch(),
              request.holdId(),
              request.holdFence(),
              request.holdBindingDigest());
      if (inserted == null || inserted.get("id", Long.class) == null) {
        throw new CanonicalInitialAdmissionConflictException(
            "Canonical initial OPEN pointer insert returned no owner row");
      }
      return inserted.get("id", Long.class);
    }

    Record pointer =
        dsl.fetchOne(
            "UPDATE gameplay_admission_pointer SET world_slug = ?, realm_slug = ?, "
                + "world_display_name = ?, realm_display_name = ?, tenant_id = ?, game_instance_id = ?, "
                + "pointer_version = ?, visible = ?, requires_character_selection = NULL, "
                + "state_scope = ?, character_creation_policy = ?, "
                + "last_updated_by = 'game-session-canonical-initial-admission', "
                + "last_update_reason = 'World-held initial admission', updated_at = ?, "
                + "public_production_realm = ?, catalog_revision = ?, playable_state_namespace_id = ?, "
                + "representation_version = 3, admission_state = 'OPEN', canonical_game_instance_id = ?, "
                + "canonical_version_id = ?, runtime_version_id = ?, initial_admission_request_id = ?, "
                + "initial_admission_request_digest = ?, initial_admission_origin_kind = ?, "
                + "initial_admission_prior_pointer_version = ?, initial_admission_active_epoch = ?, "
                + "initial_admission_hold_id = ?, initial_admission_hold_fence = ?, "
                + "initial_admission_hold_binding_digest = ? "
                + "WHERE representation_version = 2 AND target_namespace = ? "
                + "AND canonical_tenant_id = ? AND realm_id = ? AND admission_state = 'CLOSED' "
                + "AND pointer_version = ? AND catalog_revision = ? RETURNING id",
            catalog.worldSlug(),
            catalog.realmSlug(),
            catalog.sourceIntakeReceipt().source().worldDisplayName(),
            catalog.realmDisplayName(),
            association.gameSessionTenantId(),
            launchTarget.gameInstanceId(),
            pointerVersion,
            catalog.visible(),
            catalog.stateScope(),
            catalog.characterCreationPolicy(),
            toLocalDateTime(updatedAt),
            catalog.publicProduction(),
            catalog.catalogRevision(),
            catalog.playableStateNamespaceId(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            launchTarget.runtimeVersionId(),
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            request.originKind().name(),
            request.expectedPriorPointerVersion(),
            request.activeLifecycleEpoch(),
            request.holdId(),
            request.holdFence(),
            request.holdBindingDigest(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.expectedPriorPointerVersion(),
            request.expectedCatalogRevision());
    if (pointer == null || pointer.get("id", Long.class) == null) {
      throw new CanonicalInitialAdmissionConflictException(
          "Original CLOSED pointer changed before the initial OPEN CAS");
    }
    return pointer.get("id", Long.class);
  }

  private void verifyCommittedReadback(Attempt attempt) {
    CanonicalInitialAdmissionRequest request = attempt.asRequest();
    CanonicalRealmCatalogSnapshot catalog = readExactCatalog(request);
    Record evidence =
        dsl.fetchOne(
            "SELECT pointer.*, pointer.updated_at AS pointer_updated_at, "
                + "event.id AS committed_audit_event_id, event.occurred_at AS audit_at, "
                + "event.actor_principal AS audit_actor, event.reason AS audit_reason, "
                + "event.target_namespace AS audit_target_namespace, "
                + "event.canonical_tenant_id AS audit_canonical_tenant_id, "
                + "event.world_slug AS audit_world_slug, event.realm_id AS audit_realm_id, "
                + "event.world_display_name AS audit_world_display_name, "
                + "event.realm_slug AS audit_realm_slug, "
                + "event.realm_display_name AS audit_realm_display_name, "
                + "event.playable_state_namespace_id AS audit_playable_state_namespace_id, "
                + "event.state_scope AS audit_state_scope, "
                + "event.visible AS audit_visible, "
                + "event.public_production_realm AS audit_public_production_realm, "
                + "event.character_creation_policy AS audit_character_creation_policy, "
                + "event.requires_character_selection AS audit_requires_character_selection, "
                + "event.canonical_game_instance_id AS audit_canonical_game_instance_id, "
                + "event.initial_admission_request_digest AS audit_request_digest, "
                + "event.initial_admission_request_id AS audit_request_id, "
                + "event.canonical_version_id AS audit_canonical_version_id, "
                + "event.runtime_version_id AS audit_runtime_version_id, "
                + "event.initial_admission_origin_kind AS audit_origin_kind, "
                + "event.initial_admission_prior_pointer_version AS audit_prior_pointer_version, "
                + "event.initial_admission_active_epoch AS audit_active_epoch, "
                + "event.initial_admission_hold_id AS audit_hold_id, "
                + "event.initial_admission_hold_fence AS audit_hold_fence, "
                + "event.initial_admission_hold_binding_digest AS audit_hold_binding_digest, "
                + "event.tenant_id AS audit_game_session_tenant_id, "
                + "event.game_instance_id AS audit_game_instance_id, "
                + "event.pointer_version AS audit_pointer_version, "
                + "event.catalog_revision AS audit_catalog_revision, "
                + "event.control_plane_request_id AS audit_control_plane_request_id, "
                + "event.admission_state AS audit_admission_state "
                + "FROM gameplay_admission_pointer pointer JOIN gameplay_admission_pointer_event event "
                + "ON event.id = ? AND event.representation_version = 3 "
                + "AND event.target_namespace = pointer.target_namespace "
                + "AND event.canonical_tenant_id = pointer.canonical_tenant_id "
                + "AND event.realm_id = pointer.realm_id "
                + "AND event.pointer_version = pointer.pointer_version "
                + "AND event.initial_admission_request_id = pointer.initial_admission_request_id "
                + "WHERE pointer.id = ? AND pointer.representation_version = 3",
            attempt.auditEventId(),
            attempt.pointerId());
    if (evidence == null
        || !attempt.targetNamespace().equals(evidence.get("target_namespace", String.class))
        || !attempt.targetNamespace().equals(evidence.get("audit_target_namespace", String.class))
        || !attempt
            .initialAdmissionRequestId()
            .equals(evidence.get("initial_admission_request_id", String.class))
        || !attempt
            .initialAdmissionRequestId()
            .equals(evidence.get("audit_request_id", String.class))
        || !attempt
            .requestDigest()
            .equals(evidence.get("initial_admission_request_digest", String.class))
        || !attempt.requestDigest().equals(evidence.get("audit_request_digest", String.class))
        || !attempt.canonicalTenantId().equals(evidence.get("canonical_tenant_id", UUID.class))
        || !attempt
            .canonicalTenantId()
            .equals(evidence.get("audit_canonical_tenant_id", UUID.class))
        || !attempt.worldSlug().equals(evidence.get("audit_world_slug", String.class))
        || !attempt.realmId().equals(evidence.get("realm_id", UUID.class))
        || !attempt.realmId().equals(evidence.get("audit_realm_id", UUID.class))
        || !catalog
            .sourceIntakeReceipt()
            .source()
            .worldDisplayName()
            .equals(evidence.get("world_display_name", String.class))
        || !catalog
            .sourceIntakeReceipt()
            .source()
            .worldDisplayName()
            .equals(evidence.get("audit_world_display_name", String.class))
        || !catalog.realmSlug().equals(evidence.get("audit_realm_slug", String.class))
        || !catalog.realmDisplayName().equals(evidence.get("realm_display_name", String.class))
        || !catalog
            .realmDisplayName()
            .equals(evidence.get("audit_realm_display_name", String.class))
        || !attempt
            .playableStateNamespaceId()
            .equals(evidence.get("playable_state_namespace_id", UUID.class))
        || !attempt
            .playableStateNamespaceId()
            .equals(evidence.get("audit_playable_state_namespace_id", UUID.class))
        || !attempt.playableStateScope().equals(evidence.get("state_scope", String.class))
        || !attempt.playableStateScope().equals(evidence.get("audit_state_scope", String.class))
        || !Boolean.valueOf(catalog.visible()).equals(evidence.get("visible", Boolean.class))
        || !Boolean.valueOf(catalog.visible()).equals(evidence.get("audit_visible", Boolean.class))
        || !Boolean.valueOf(catalog.publicProduction())
            .equals(evidence.get("public_production_realm", Boolean.class))
        || !Boolean.valueOf(catalog.publicProduction())
            .equals(evidence.get("audit_public_production_realm", Boolean.class))
        || !catalog
            .characterCreationPolicy()
            .equals(evidence.get("character_creation_policy", String.class))
        || !catalog
            .characterCreationPolicy()
            .equals(evidence.get("audit_character_creation_policy", String.class))
        || evidence.get("requires_character_selection", Boolean.class) != null
        || evidence.get("audit_requires_character_selection", Boolean.class) != null
        || !attempt
            .canonicalGameInstanceId()
            .equals(evidence.get("canonical_game_instance_id", UUID.class))
        || !attempt
            .canonicalGameInstanceId()
            .equals(evidence.get("audit_canonical_game_instance_id", UUID.class))
        || !attempt.canonicalVersionId().equals(evidence.get("canonical_version_id", UUID.class))
        || attempt.runtimeVersionId() != evidence.get("runtime_version_id", Long.class)
        || !attempt
            .canonicalVersionId()
            .equals(evidence.get("audit_canonical_version_id", UUID.class))
        || attempt.runtimeVersionId() != evidence.get("audit_runtime_version_id", Long.class)
        || attempt.gameSessionTenantId() != evidence.get("tenant_id", Long.class)
        || attempt.gameSessionTenantId() != evidence.get("audit_game_session_tenant_id", Long.class)
        || attempt.gameInstanceId() != evidence.get("game_instance_id", Long.class)
        || attempt.gameInstanceId() != evidence.get("audit_game_instance_id", Long.class)
        || !pointerVersionMatches(
            attempt.pointerVersion(), evidence.get("pointer_version", Long.class))
        || !pointerVersionMatches(
            attempt.pointerVersion(), evidence.get("audit_pointer_version", Long.class))
        || attempt.expectedCatalogRevision() != evidence.get("catalog_revision", Long.class)
        || attempt.expectedCatalogRevision() != evidence.get("audit_catalog_revision", Long.class)
        || !"OPEN".equals(evidence.get("admission_state", String.class))
        || !"OPEN".equals(evidence.get("audit_admission_state", String.class))
        || attempt.originKind()
            != CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                evidence.get("initial_admission_origin_kind", String.class))
        || attempt.originKind()
            != CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                evidence.get("audit_origin_kind", String.class))
        || !Objects.equals(
            attempt.expectedPriorPointerVersion(),
            evidence.get("initial_admission_prior_pointer_version", Long.class))
        || !Objects.equals(
            attempt.expectedPriorPointerVersion(),
            evidence.get("audit_prior_pointer_version", Long.class))
        || attempt.activeLifecycleEpoch()
            != evidence.get("initial_admission_active_epoch", Long.class)
        || attempt.activeLifecycleEpoch() != evidence.get("audit_active_epoch", Long.class)
        || !attempt.holdId().equals(evidence.get("initial_admission_hold_id", UUID.class))
        || !attempt.holdId().equals(evidence.get("audit_hold_id", UUID.class))
        || !attempt.holdFence().equals(evidence.get("initial_admission_hold_fence", UUID.class))
        || !attempt.holdFence().equals(evidence.get("audit_hold_fence", UUID.class))
        || !attempt
            .holdBindingDigest()
            .equals(evidence.get("initial_admission_hold_binding_digest", String.class))
        || !AUDIT_ACTOR.equals(evidence.get("audit_actor", String.class))
        || !AUDIT_REASON.equals(evidence.get("audit_reason", String.class))
        || !attempt
            .holdBindingDigest()
            .equals(evidence.get("audit_hold_binding_digest", String.class))
        || !attempt
            .initialAdmissionRequestId()
            .equals(evidence.get("audit_control_plane_request_id", String.class))
        || !attempt
            .terminalAt()
            .equals(toInstant(evidence.get("pointer_updated_at", java.time.LocalDateTime.class)))
        || !attempt
            .terminalAt()
            .equals(toInstant(evidence.get("audit_at", java.time.LocalDateTime.class)))
        || !catalog.worldSlug().equals(evidence.get("world_slug", String.class))
        || !catalog.realmSlug().equals(evidence.get("realm_slug", String.class))
        || !catalog
            .playableStateNamespaceId()
            .equals(evidence.get("playable_state_namespace_id", UUID.class))) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Committed initial-admission pointer, audit, request and catalog readback disagree");
    }
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED) {
      verifyOriginalClosedOrigin(attempt, false);
    }
    String expectedProof =
        commitProofDigest(
            attempt,
            attempt.pointerVersion(),
            attempt.auditEventId(),
            attempt.terminalAt(),
            attempt.priorPointerRequestId(),
            attempt.priorPointerRequestDigest(),
            attempt.priorPointerAuditEventId());
    if (!expectedProof.equals(attempt.commitProofDigest())) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Committed initial-admission proof digest does not match the durable result");
    }
  }

  private void verifyAbortReadback(Attempt attempt) {
    readExactCatalog(attempt.asRequest());
    if (attempt.pointerId() != null
        || attempt.pointerVersion() != null
        || attempt.auditEventId() != null
        || attempt.abortProofDigest() == null
        || attempt.commitProofDigest() != null) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Durable ABORTED attempt contains contradictory pointer evidence");
    }
    if (hasAttemptPointerEvidence(attempt)) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "ABORTED initial-admission request unexpectedly has pointer or audit evidence");
    }
    if (attempt.originKind() == CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED) {
      // The ABORTED terminal result is historical. A later distinct request may legitimately open
      // this route; retain and verify the original CLOSED request/event without mistaking the later
      // OPEN row for the terminal result of this aborted request.
      verifyOriginalClosedOrigin(attempt, false);
    }
    String expectedProof =
        abortProofDigest(
            attempt,
            attempt.abortReason(),
            attempt.terminalAt(),
            attempt.priorPointerRequestId(),
            attempt.priorPointerRequestDigest(),
            attempt.priorPointerAuditEventId());
    if (!expectedProof.equals(attempt.abortProofDigest())) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Durable ABORTED proof digest does not match the fenced result");
    }
  }

  private void verifyOriginalClosedOrigin(Attempt attempt, boolean requireCurrent) {
    if (attempt.priorPointerRequestId() == null
        || attempt.priorPointerRequestDigest() == null
        || attempt.priorPointerAuditEventId() == null
        || !Long.valueOf(1L).equals(attempt.expectedPriorPointerVersion())) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "EXPECT_CLOSED terminal result has no exact original CLOSED origin identity");
    }
    CanonicalClosedAdmissionPointerSnapshot origin =
        (requireCurrent
                ? pointerRepository.readByRequest(
                    attempt.targetNamespace(), attempt.priorPointerRequestId())
                : pointerRepository.readOriginalClosedOrigin(
                    attempt.targetNamespace(), attempt.priorPointerRequestId()))
            .orElseThrow(
                () ->
                    new CanonicalInitialAdmissionReconciliationRequiredException(
                        requireCurrent
                            ? "Original CLOSED route is no longer current owner authority"
                            : "Immutable original CLOSED request/event evidence is unavailable"));
    if (!origin.canonicalTenantId().equals(attempt.canonicalTenantId())
        || !origin.realmId().equals(attempt.realmId())
        || !origin.requestDigest().equals(attempt.priorPointerRequestDigest())
        || origin.auditEventId() != attempt.priorPointerAuditEventId()
        || origin.pointerVersion() != attempt.expectedPriorPointerVersion()
        || origin.catalogRevision() != attempt.expectedCatalogRevision()) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Original CLOSED request/event proof changed or no longer matches the attempt");
    }
  }

  static boolean pointerVersionMatches(Long attemptVersion, Long storedVersion) {
    return Objects.equals(attemptVersion, storedVersion);
  }

  private boolean hasAttemptPointerEvidence(Attempt attempt) {
    Record exists =
        dsl.fetchOne(
            "SELECT EXISTS ("
                + "SELECT 1 FROM gameplay_admission_pointer WHERE representation_version = 3 "
                + "AND target_namespace = ? AND initial_admission_request_id = ? "
                + "UNION ALL SELECT 1 FROM gameplay_admission_pointer_event "
                + "WHERE representation_version = 3 AND target_namespace = ? "
                + "AND initial_admission_request_id = ?) AS represented",
            attempt.targetNamespace(),
            attempt.initialAdmissionRequestId(),
            attempt.targetNamespace(),
            attempt.initialAdmissionRequestId());
    return exists == null || !Boolean.FALSE.equals(exists.get("represented", Boolean.class));
  }

  private CanonicalRealmCatalogSnapshot readExactCatalog(CanonicalInitialAdmissionRequest request) {
    CanonicalRealmCatalogSnapshot catalog =
        catalogRepository
            .readByRealm(request.targetNamespace(), request.canonicalTenantId(), request.realmId())
            .orElseThrow(
                () ->
                    new CanonicalInitialAdmissionReconciliationRequiredException(
                        "Exact canonical realm catalog is unavailable for owner readback"));
    requireRequestMatchesCatalog(request, catalog);
    return catalog;
  }

  private CanonicalInitialAdmissionLaunchTarget readExactLaunchTarget(
      CanonicalInitialAdmissionRequest request) {
    CanonicalInitialAdmissionLaunchTarget target =
        launchRepository
            .readForInitialAdmission(
                request.targetNamespace(),
                request.canonicalTenantId(),
                request.realmId(),
                request.canonicalGameInstanceId())
            .orElseThrow(
                () ->
                    new CanonicalInitialAdmissionReconciliationRequiredException(
                        "Exact canonical launch association is unavailable for owner readback"));
    requireRequestMatchesLaunch(request, target);
    return target;
  }

  private CanonicalRealmCatalogSnapshot lockCatalog(CanonicalInitialAdmissionRequest request) {
    CanonicalRealmCatalogSnapshot catalog =
        catalogRepository.lockInitialAdmissionTarget(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.expectedCatalogRevision());
    requireRequestMatchesCatalog(request, catalog);
    return catalog;
  }

  private CanonicalInitialAdmissionLaunchTarget lockLaunchTarget(
      CanonicalInitialAdmissionRequest request, boolean requireRunning) {
    CanonicalInitialAdmissionLaunchTarget launchTarget =
        launchRepository.lockForInitialAdmission(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.canonicalGameInstanceId(),
            requireRunning);
    requireRequestMatchesLaunch(request, launchTarget);
    return launchTarget;
  }

  private static void requireRequestMatchesCatalog(
      CanonicalInitialAdmissionRequest request, CanonicalRealmCatalogSnapshot catalog) {
    if (!request.targetNamespace().equals(catalog.targetNamespace())
        || !request.canonicalTenantId().equals(catalog.tenantId())
        || !request.worldSlug().equals(catalog.worldSlug())
        || !request.realmId().equals(catalog.realmId())
        || !request.playableStateNamespaceId().equals(catalog.playableStateNamespaceId())
        || !request.playableStateScope().equals(catalog.stateScope())
        || request.expectedCatalogRevision() != catalog.catalogRevision()
        || !catalog.visible()
        || !catalog.publicProduction()) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Canonical realm catalog does not match the exact initial-admission request");
    }
  }

  private static void requireRequestMatchesLaunch(
      CanonicalInitialAdmissionRequest request,
      CanonicalInitialAdmissionLaunchTarget launchTarget) {
    CanonicalGameInstanceLaunchAssociation association = launchTarget.association();
    if (!request.targetNamespace().equals(association.targetNamespace())
        || !request.canonicalTenantId().equals(association.canonicalTenantId())
        || !request.worldSlug().equals(association.worldSlug())
        || !request.realmId().equals(launchTarget.realmId())
        || !request.playableStateNamespaceId().equals(association.playableStateNamespaceId())
        || !request.playableStateScope().equals(association.playableStateScope().name())
        || !request.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !request.canonicalVersionId().equals(launchTarget.canonicalVersionId())
        || !association.publicProduction()) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Canonical launch association does not match the exact initial-admission target tuple");
    }
  }

  private static void requireRequestMatchesCatalogAndLaunch(
      CanonicalInitialAdmissionRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launchTarget) {
    requireRequestMatchesCatalog(request, catalog);
    requireRequestMatchesLaunch(request, launchTarget);
  }

  private void lockTarget(CanonicalInitialAdmissionRequest request) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ? || ':' || ?, 0))",
        request.targetNamespace(),
        request.canonicalTenantId().toString(),
        request.realmId().toString());
  }

  /**
   * Existing pointer and legacy writers do not participate in this canonical target lock. Briefly
   * excluding every representation table closes the otherwise-open absence-check gap; V9 numeric
   * identifiers are used only to find collisions, never as canonical proof.
   */
  private void lockAllAdmissionRepresentationsForNoPrior() {
    dsl.execute(
        "LOCK TABLE gameplay_admission_pointer, gameplay_admission_pointer_event, "
            + "game_session_canonical_closed_admission_pointer_request, "
            + "gameplay_initial_admission_bind_catalog, gameplay_initial_admission_bind_attempt "
            + "IN SHARE ROW EXCLUSIVE MODE");
  }

  private Attempt requireAttemptForUpdate(CanonicalInitialAdmissionRequest request) {
    Attempt attempt =
        findAttempt(request.targetNamespace(), request.initialAdmissionRequestId(), true);
    if (attempt == null) {
      throw new CanonicalInitialAdmissionReconciliationRequiredException(
          "Initial-admission request was not durably reserved before owner proof or commit");
    }
    return attempt;
  }

  private void requireSameRequest(
      Attempt attempt,
      CanonicalInitialAdmissionRequest request,
      CanonicalInitialAdmissionLaunchTarget launchTarget) {
    requireSameRequestIdentity(attempt, request);
    if (attempt.runtimeVersionId() != launchTarget.runtimeVersionId()
        || attempt.gameSessionTenantId() != launchTarget.association().gameSessionTenantId()
        || attempt.gameInstanceId() != launchTarget.gameInstanceId()) {
      throw new CanonicalInitialAdmissionConflictException(
          "Initial-admission request identity was reused with a changed canonical tuple or digest");
    }
  }

  private void requireSameRequestIdentity(
      Attempt attempt, CanonicalInitialAdmissionRequest request) {
    if (!attempt.targetNamespace().equals(request.targetNamespace())
        || !attempt.initialAdmissionRequestId().equals(request.initialAdmissionRequestId())
        || !attempt.requestDigest().equals(request.requestDigest())
        || !attempt.canonicalTenantId().equals(request.canonicalTenantId())
        || !attempt.worldSlug().equals(request.worldSlug())
        || !attempt.realmId().equals(request.realmId())
        || !attempt.playableStateNamespaceId().equals(request.playableStateNamespaceId())
        || !attempt.playableStateScope().equals(request.playableStateScope())
        || !attempt.canonicalGameInstanceId().equals(request.canonicalGameInstanceId())
        || !attempt.canonicalVersionId().equals(request.canonicalVersionId())
        || !attempt.holdBindingDigest().equals(request.holdBindingDigest())
        || attempt.expectedCatalogRevision() != request.expectedCatalogRevision()
        || attempt.originKind() != request.originKind()
        || !Objects.equals(
            attempt.expectedPriorPointerVersion(), request.expectedPriorPointerVersion())
        || attempt.activeLifecycleEpoch() != request.activeLifecycleEpoch()
        || !attempt.holdId().equals(request.holdId())
        || !attempt.holdFence().equals(request.holdFence())) {
      throw new CanonicalInitialAdmissionConflictException(
          "Initial-admission request identity was reused with a changed canonical tuple or digest");
    }
  }

  private Attempt findPendingForRealm(CanonicalInitialAdmissionRequest request, boolean forUpdate) {
    String sql =
        "SELECT * FROM "
            + OPERATION_TABLE
            + " WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? "
            + "AND status = 'PENDING'"
            + (forUpdate ? " FOR UPDATE" : "");
    return Optional.ofNullable(
            dsl.fetchOne(
                sql, request.targetNamespace(), request.canonicalTenantId(), request.realmId()))
        .map(CanonicalInitialAdmissionRepository::toAttempt)
        .orElse(null);
  }

  private Attempt findAttempt(String targetNamespace, String requestId, boolean forUpdate) {
    return Optional.ofNullable(findAttemptRecord(targetNamespace, requestId, forUpdate))
        .map(CanonicalInitialAdmissionRepository::toAttempt)
        .orElse(null);
  }

  private Record findAttemptRecord(String targetNamespace, String requestId, boolean forUpdate) {
    if (targetNamespace == null || requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("Initial-admission read selector is incomplete");
    }
    return dsl.fetchOne(
        "SELECT * FROM "
            + OPERATION_TABLE
            + " WHERE target_namespace = ? AND initial_admission_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        targetNamespace,
        requestId);
  }

  private static Attempt toAttempt(Record row) {
    return new Attempt(
        row.get("target_namespace", String.class),
        row.get("initial_admission_request_id", String.class),
        row.get("request_digest", String.class),
        row.get("canonical_tenant_id", UUID.class),
        row.get("world_slug", String.class),
        row.get("realm_id", UUID.class),
        row.get("playable_state_namespace_id", UUID.class),
        row.get("playable_state_scope", String.class),
        row.get("canonical_game_instance_id", UUID.class),
        row.get("canonical_version_id", UUID.class),
        row.get("game_session_tenant_id", Long.class),
        row.get("game_instance_id", Long.class),
        row.get("runtime_version_id", Long.class),
        row.get("hold_binding_digest", String.class),
        row.get("expected_catalog_revision", Long.class),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(row.get("origin_kind", String.class)),
        row.get("expected_prior_pointer_version", Long.class),
        row.get("active_lifecycle_epoch", Long.class),
        row.get("hold_id", UUID.class),
        row.get("hold_fence", UUID.class),
        row.get("status", String.class),
        row.get("pointer_id", Long.class),
        row.get("pointer_version", Long.class),
        row.get("audit_event_id", Long.class),
        row.get("prior_pointer_request_id", UUID.class),
        row.get("prior_pointer_request_digest", String.class),
        row.get("prior_pointer_audit_event_id", Long.class),
        row.get("commit_proof_digest", String.class),
        row.get("abort_proof_digest", String.class),
        row.get("abort_reason", String.class),
        row.get("created_at", OffsetDateTime.class).toInstant(),
        row.get("updated_at", OffsetDateTime.class).toInstant(),
        row.get("terminal_at", OffsetDateTime.class) == null
            ? null
            : row.get("terminal_at", OffsetDateTime.class).toInstant());
  }

  private static CanonicalInitialAdmissionOwnerProof toProof(
      Attempt attempt, CanonicalInitialAdmissionOwnerProof.Outcome outcome) {
    return new CanonicalInitialAdmissionOwnerProof(
        outcome,
        attempt.initialAdmissionRequestId(),
        attempt.requestDigest(),
        attempt.targetNamespace(),
        attempt.canonicalTenantId(),
        attempt.worldSlug(),
        attempt.realmId(),
        attempt.playableStateNamespaceId(),
        attempt.playableStateScope(),
        attempt.canonicalGameInstanceId(),
        attempt.canonicalVersionId(),
        attempt.activeLifecycleEpoch(),
        attempt.expectedCatalogRevision(),
        attempt.originKind(),
        attempt.expectedPriorPointerVersion(),
        attempt.holdId(),
        attempt.holdFence(),
        attempt.holdBindingDigest(),
        attempt.pointerVersion(),
        attempt.auditEventId(),
        COMMITTED.equals(attempt.status())
            ? attempt.commitProofDigest()
            : ABORTED.equals(attempt.status()) ? attempt.abortProofDigest() : null,
        ABORTED.equals(attempt.status()),
        attempt.terminalAt());
  }

  private static String commitProofDigest(
      Attempt attempt,
      long pointerVersion,
      long auditEventId,
      Instant terminalAt,
      UUID priorRequestId,
      String priorRequestDigest,
      Long priorAuditEventId) {
    return digest(
        List.of(
            COMMIT_PROOF_DOMAIN,
            attempt.requestDigest(),
            attempt.initialAdmissionRequestId(),
            attempt.targetNamespace(),
            attempt.canonicalTenantId().toString(),
            attempt.worldSlug(),
            attempt.realmId().toString(),
            attempt.playableStateNamespaceId().toString(),
            attempt.playableStateScope(),
            attempt.canonicalGameInstanceId().toString(),
            attempt.canonicalVersionId().toString(),
            Long.toString(attempt.runtimeVersionId()),
            Long.toString(attempt.activeLifecycleEpoch()),
            Long.toString(attempt.expectedCatalogRevision()),
            attempt.originKind().name(),
            attempt.expectedPriorPointerVersion() == null
                ? ""
                : attempt.expectedPriorPointerVersion().toString(),
            attempt.holdId().toString(),
            attempt.holdFence().toString(),
            attempt.holdBindingDigest(),
            Long.toString(pointerVersion),
            Long.toString(auditEventId),
            priorRequestId == null ? "" : priorRequestId.toString(),
            priorRequestDigest == null ? "" : priorRequestDigest,
            priorAuditEventId == null ? "" : priorAuditEventId.toString(),
            terminalAt.toString()));
  }

  private static String abortProofDigest(
      Attempt attempt,
      String reason,
      Instant terminalAt,
      UUID priorRequestId,
      String priorRequestDigest,
      Long priorAuditEventId) {
    return digest(
        List.of(
            ABORT_PROOF_DOMAIN,
            attempt.requestDigest(),
            attempt.initialAdmissionRequestId(),
            attempt.targetNamespace(),
            attempt.canonicalTenantId().toString(),
            attempt.worldSlug(),
            attempt.realmId().toString(),
            attempt.playableStateNamespaceId().toString(),
            attempt.playableStateScope(),
            attempt.canonicalGameInstanceId().toString(),
            attempt.canonicalVersionId().toString(),
            Long.toString(attempt.runtimeVersionId()),
            Long.toString(attempt.activeLifecycleEpoch()),
            Long.toString(attempt.expectedCatalogRevision()),
            attempt.originKind().name(),
            attempt.expectedPriorPointerVersion() == null
                ? ""
                : attempt.expectedPriorPointerVersion().toString(),
            attempt.holdId().toString(),
            attempt.holdFence().toString(),
            attempt.holdBindingDigest(),
            reason,
            priorRequestId == null ? "" : priorRequestId.toString(),
            priorRequestDigest == null ? "" : priorRequestDigest,
            priorAuditEventId == null ? "" : priorAuditEventId.toString(),
            terminalAt.toString()));
  }

  private static String digest(List<String> fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String field : fields) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Instant nextTimestamp(Instant previous) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    if (previous != null && !now.isAfter(previous)) {
      return previous.plus(1, ChronoUnit.MICROS);
    }
    return now;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical initial-admission owner mutation requires a writable transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Canonical initial-admission owner mutation requires READ COMMITTED isolation");
          }
          return null;
        });
    Record isolation =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (isolation == null
        || !"read committed".equals(isolation.get("isolation", String.class))
        || !"off".equals(isolation.get("read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical initial-admission owner mutation requires writable READ COMMITTED isolation");
    }
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical initial-admission owner proof requires a committed readback");
    }
  }

  private static void requireText(String value, String label, int maxLength) {
    Objects.requireNonNull(value, label);
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(label + " must contain 1.." + maxLength + " characters");
    }
  }

  /**
   * Full persisted pointer-row identity used to detect any before/after read drift.
   *
   * <p>Package-private test seam keeps projection validation directly testable without reflection.
   */
  record CurrentOpenPointer(Map<String, Object> fields) {
    CurrentOpenPointer {
      fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    private static CurrentOpenPointer from(Record row) {
      return new CurrentOpenPointer(row.intoMap());
    }

    private String initialAdmissionRequestId() {
      return value("initial_admission_request_id", String.class);
    }

    private void requireCanonicalOpen(
        String targetNamespace, UUID canonicalTenantId, UUID realmId) {
      if (!Integer.valueOf(3).equals(value("representation_version", Integer.class))
          || !"OPEN".equals(value("admission_state", String.class))
          || !targetNamespace.equals(value("target_namespace", String.class))
          || !canonicalTenantId.equals(value("canonical_tenant_id", UUID.class))
          || !realmId.equals(value("realm_id", UUID.class))
          || !Boolean.TRUE.equals(value("visible", Boolean.class))
          || !Boolean.TRUE.equals(value("public_production_realm", Boolean.class))
          || value("requires_character_selection", Boolean.class) != null
          || !"SHARED".equals(value("state_scope", String.class))
          || !AUDIT_ACTOR.equals(value("last_updated_by", String.class))
          || !AUDIT_REASON.equals(value("last_update_reason", String.class))
          || value("prepared_version_upgrade_id", String.class) != null
          || !text(value("character_creation_policy", String.class))
          || !positive(value("id", Long.class))
          || !positive(value("pointer_version", Long.class))
          || !positive(value("catalog_revision", Long.class))
          || !positive(value("tenant_id", Long.class))
          || !positive(value("game_instance_id", Long.class))
          || !positive(value("runtime_version_id", Long.class))
          || !positive(value("initial_admission_active_epoch", Long.class))
          || !nonNil(value("playable_state_namespace_id", UUID.class))
          || !nonNil(value("canonical_game_instance_id", UUID.class))
          || !nonNil(value("canonical_version_id", UUID.class))
          || !nonNil(value("initial_admission_hold_id", UUID.class))
          || !nonNil(value("initial_admission_hold_fence", UUID.class))
          || !text(value("world_slug", String.class))
          || !text(value("realm_slug", String.class))
          || !text(value("initial_admission_request_id", String.class))
          || !matchesDigest(value("initial_admission_request_digest", String.class))
          || !text(value("initial_admission_origin_kind", String.class))
          || !matchesProofDigest(value("initial_admission_hold_binding_digest", String.class))) {
        throw new CanonicalInitialAdmissionReconciliationRequiredException(
            "Current realm pointer is not a complete canonical OPEN representation");
      }
    }

    private boolean matches(CanonicalInitialAdmissionOwnerProof proof) {
      return proof.provesCommit()
          && proof.targetNamespace().equals(value("target_namespace", String.class))
          && proof.canonicalTenantId().equals(value("canonical_tenant_id", UUID.class))
          && proof.worldSlug().equals(value("world_slug", String.class))
          && proof.realmId().equals(value("realm_id", UUID.class))
          && proof
              .playableStateNamespaceId()
              .equals(value("playable_state_namespace_id", UUID.class))
          && proof.playableStateScope().equals(value("state_scope", String.class))
          && proof.canonicalGameInstanceId().equals(value("canonical_game_instance_id", UUID.class))
          && proof.canonicalVersionId().equals(value("canonical_version_id", UUID.class))
          && proof.expectedCatalogRevision() == value("catalog_revision", Long.class)
          && proof.committedPointerVersion().equals(value("pointer_version", Long.class))
          && proof
              .initialAdmissionRequestId()
              .equals(value("initial_admission_request_id", String.class))
          && proof.requestDigest().equals(value("initial_admission_request_digest", String.class))
          && proof.originKind().name().equals(value("initial_admission_origin_kind", String.class))
          && Objects.equals(
              proof.expectedPriorPointerVersion(),
              value("initial_admission_prior_pointer_version", Long.class))
          && proof.activeLifecycleEpoch() == value("initial_admission_active_epoch", Long.class)
          && proof.holdId().equals(value("initial_admission_hold_id", UUID.class))
          && proof.holdFence().equals(value("initial_admission_hold_fence", UUID.class))
          && proof
              .holdBindingDigest()
              .equals(value("initial_admission_hold_binding_digest", String.class))
          && proof.auditEventId() != null
          && proof.auditEventId() > 0L;
    }

    private String snapshotDigest(CanonicalInitialAdmissionOwnerProof proof) {
      return CanonicalCurrentOpenPointerSnapshotDigest.digest(
          new CanonicalCurrentOpenPointerSnapshotDigest.Projection(
              value("representation_version", Integer.class),
              value("target_namespace", String.class),
              value("canonical_tenant_id", UUID.class),
              value("world_slug", String.class),
              value("world_display_name", String.class),
              value("realm_id", UUID.class),
              value("realm_slug", String.class),
              value("realm_display_name", String.class),
              value("playable_state_namespace_id", UUID.class),
              value("state_scope", String.class),
              value("catalog_revision", Long.class),
              value("pointer_version", Long.class),
              value("admission_state", String.class),
              value("visible", Boolean.class),
              value("public_production_realm", Boolean.class),
              value("requires_character_selection", Boolean.class),
              value("character_creation_policy", String.class),
              value("canonical_game_instance_id", UUID.class),
              value("canonical_version_id", UUID.class),
              value("initial_admission_request_id", String.class),
              value("initial_admission_request_digest", String.class),
              CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                  value("initial_admission_origin_kind", String.class)),
              value("initial_admission_prior_pointer_version", Long.class),
              value("initial_admission_active_epoch", Long.class),
              value("initial_admission_hold_id", UUID.class),
              value("initial_admission_hold_fence", UUID.class),
              value("initial_admission_hold_binding_digest", String.class),
              value("last_updated_by", String.class),
              value("last_update_reason", String.class),
              value("prepared_version_upgrade_id", String.class),
              proof.proofDigest(),
              proof.auditEventId(),
              proof.terminalAt()));
    }

    private <T> T value(String key, Class<T> type) {
      Object value = fields.get(key);
      if (value == null) {
        return null;
      }
      if (!type.isInstance(value)) {
        throw new CanonicalInitialAdmissionReconciliationRequiredException(
            "Current realm pointer contains an invalid persisted field type: " + key);
      }
      return type.cast(value);
    }

    private static boolean positive(Long value) {
      return value != null && value > 0L;
    }

    private static boolean nonNil(UUID value) {
      return value != null && !new UUID(0L, 0L).equals(value);
    }

    private static boolean text(String value) {
      return value != null && !value.isBlank() && value.equals(value.trim());
    }

    private static boolean matchesDigest(String value) {
      return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean matchesProofDigest(String value) {
      return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
  }

  private record Attempt(
      String targetNamespace,
      String initialAdmissionRequestId,
      String requestDigest,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      long gameSessionTenantId,
      long gameInstanceId,
      long runtimeVersionId,
      String holdBindingDigest,
      long expectedCatalogRevision,
      CanonicalInitialAdmissionRequest.OriginKind originKind,
      Long expectedPriorPointerVersion,
      long activeLifecycleEpoch,
      UUID holdId,
      UUID holdFence,
      String status,
      Long pointerId,
      Long pointerVersion,
      Long auditEventId,
      UUID priorPointerRequestId,
      String priorPointerRequestDigest,
      Long priorPointerAuditEventId,
      String commitProofDigest,
      String abortProofDigest,
      String abortReason,
      Instant createdAt,
      Instant updatedAt,
      Instant terminalAt) {
    private CanonicalInitialAdmissionRequest asRequest() {
      return new CanonicalInitialAdmissionRequest(
          targetNamespace,
          canonicalTenantId,
          worldSlug,
          realmId,
          playableStateNamespaceId,
          playableStateScope,
          canonicalGameInstanceId,
          canonicalVersionId,
          activeLifecycleEpoch,
          expectedCatalogRevision,
          originKind,
          expectedPriorPointerVersion,
          initialAdmissionRequestId,
          requestDigest,
          holdId,
          holdFence,
          holdBindingDigest);
    }
  }

  public static class CanonicalInitialAdmissionConflictException extends IllegalStateException {
    public CanonicalInitialAdmissionConflictException(String message) {
      super(message);
    }
  }

  public static class CanonicalInitialAdmissionReconciliationRequiredException
      extends IllegalStateException {
    public CanonicalInitialAdmissionReconciliationRequiredException(String message) {
      super(message);
    }

    public CanonicalInitialAdmissionReconciliationRequiredException(
        String message, Throwable cause) {
      super(message, cause);
    }
  }
}
