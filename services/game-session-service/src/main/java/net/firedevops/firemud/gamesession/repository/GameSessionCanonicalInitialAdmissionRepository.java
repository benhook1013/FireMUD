package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Non-activating Game Session owner for one canonical first-OPEN admission.
 *
 * <p>This owner composes existing launch, lifecycle, original-attempt, World-hold, Account
 * protection, and V28 pointer/audit records. Its byte decoding and SQL checks provide integrity,
 * not remote Account or World authentication. The caller remains responsible for authenticating
 * those producers and for settling Account only from this repository's independently read exact
 * terminal result.
 */
public final class GameSessionCanonicalInitialAdmissionRepository {
  private static final String INTENT_TABLE = "game_session_canonical_initial_admission_intent";
  private static final String ATTEMPT_TABLE = "game_session_canonical_initial_admission_attempt";
  private static final String TERMINAL_DIGEST_DOMAIN =
      "game-session-canonical-initial-admission-terminal/v1";
  private static final DateTimeFormatter MICROS_UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final GameSessionStartSessionOperatorAttemptRepository originalAttemptRepository;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public GameSessionCanonicalInitialAdmissionRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      GameSessionStartSessionOperatorAttemptRepository originalAttemptRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is missing or invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.originalAttemptRepository =
        Objects.requireNonNull(originalAttemptRepository, "originalAttemptRepository");
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readTransaction.setReadOnly(true);
  }

  /**
   * Persists the exact V28 hold intent before World acquisition and returns its immutable request.
   * No remote call is made here. An exact retry returns the previously retained request.
   */
  public WorldCanonicalInitialAdmissionHold.Request beginIntent(
      AttemptClaim claim, IntentInput input) {
    requireOutsideOwnerTransaction();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(input, "input");
    PreparedIntent prepared = prepareIntent(input);
    WorldCanonicalInitialAdmissionHold.Request result =
        writeTransaction.execute(status -> beginIntentInTransaction(claim, prepared));
    if (result == null) {
      throw invalid("Initial-admission intent transaction returned no request");
    }
    WorldCanonicalInitialAdmissionHold.Request readback =
        readTransaction.execute(status -> readIntentRequest(prepared));
    if (readback == null
        || !Arrays.equals(result.canonicalRequestBytes(), readback.canonicalRequestBytes())) {
      throw invalid("Committed initial-admission intent differs on independent readback");
    }
    return readback;
  }

  /**
   * Attaches the exact World-issued immutable hold identity to the V28 intent and creates its one
   * PENDING V28 owner row. This records identity only; it does not assert hold activity or admit a
   * player.
   */
  public CanonicalInitialAdmissionRequest attachWorldHold(
      AttemptClaim claim, WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity) {
    requireOutsideOwnerTransaction();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    CanonicalInitialAdmissionRequest result =
        writeTransaction.execute(status -> attachWorldHoldInTransaction(claim, holdIdentity));
    if (result == null) {
      throw invalid("Initial-admission hold attachment transaction returned no request");
    }
    return result;
  }

  /** Terminalizes the exact held first-OPEN intent as COMMITTED. */
  public OriginalStartSessionAdmissionTerminalResult commit(
      AttemptClaim claim, AccountStartSessionAdmissionProtectionEvidence protectionEvidence) {
    return terminalize(claim, protectionEvidence, TerminalAction.COMMIT, null);
  }

  /**
   * Records only a definitive V28 ABORTED outcome. The SQL owner guard must prove that the original
   * origin remains excluded before this result is durable.
   */
  public OriginalStartSessionAdmissionTerminalResult abort(
      AttemptClaim claim,
      AccountStartSessionAdmissionProtectionEvidence protectionEvidence,
      String reason) {
    requireText(reason, "reason", 500);
    return terminalize(claim, protectionEvidence, TerminalAction.ABORT, reason);
  }

  /**
   * Independently reads one exact terminal result. An absent or PENDING attempt is empty; a
   * terminal-but-incomplete, mismatched, or corrupt owner record is an error.
   */
  public Optional<OriginalStartSessionAdmissionTerminalResult> readTerminal(
      OriginalStartSessionAdmissionTerminalRequest request) {
    requireOutsideOwnerTransaction();
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw invalid("Terminal selector namespace differs from this Game Session owner");
    }
    Optional<OriginalStartSessionAdmissionTerminalResult> result =
        readTransaction.execute(status -> readTerminalInTransaction(request));
    return Objects.requireNonNull(result, "Terminal read transaction returned no result");
  }

  private OriginalStartSessionAdmissionTerminalResult terminalize(
      AttemptClaim claim,
      AccountStartSessionAdmissionProtectionEvidence protectionEvidence,
      TerminalAction action,
      String abortReason) {
    requireOutsideOwnerTransaction();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(protectionEvidence, "protectionEvidence");
    OriginalStartSessionAdmissionTerminalRequest selector =
        new OriginalStartSessionAdmissionTerminalRequest(protectionEvidence.canonicalBytes());

    // Exact historical terminal recovery is deliberately the first operation. It does not
    // extend or revalidate the already elapsed original claim/lease.
    Optional<OriginalStartSessionAdmissionTerminalResult> existing = readTerminal(selector);
    if (existing.isPresent()) {
      return requireRequestedTerminal(existing.get(), action, abortReason);
    }

    TerminalWriteResult written =
        writeTransaction.execute(
            status -> terminalizeInTransaction(claim, protectionEvidence, action, abortReason));
    if (written == null) {
      throw invalid("Initial-admission terminal transaction returned no result");
    }
    Optional<OriginalStartSessionAdmissionTerminalResult> independent = readTerminal(selector);
    if (independent.isEmpty()) {
      throw invalid("Terminal owner write has no independent exact terminal readback");
    }
    return requireRequestedTerminal(independent.get(), action, abortReason);
  }

  private TerminalWriteResult terminalizeInTransaction(
      AttemptClaim claim,
      AccountStartSessionAdmissionProtectionEvidence evidence,
      TerminalAction action,
      String abortReason) {
    requireWritableReadCommittedOwnerTransaction();
    AccountStartSessionAdmissionProtectionRequest protection = evidence.request();
    WorldCanonicalInitialAdmissionHold.HoldIdentity hold = protection.worldAdmissionHoldIdentity();
    WorldCanonicalInitialAdmissionHold.Request holdRequest = hold.request();
    String initialRequestId = holdRequest.initialAdmissionRequestId();
    String controlRequestId = protection.originalTuple().controlPlaneRequestId();
    String namespace = workloadNamespace;

    lockRequest(namespace, initialRequestId);
    lockTenantAndLaunch(namespace, holdRequest.canonicalTenantId(), controlRequestId);

    Record intent = selectIntent(namespace, initialRequestId, true);
    Record attempt = selectInitialAttempt(namespace, initialRequestId, true);
    if (intent == null || attempt == null) {
      throw invalid("Exact attached initial-admission intent and owner attempt are unavailable");
    }
    if (isTerminal(requiredText(attempt, "status"))) {
      // A concurrent exact retry won the request lock. Do no further work or claim check here;
      // the public method performs a new independent terminal read after this transaction ends.
      return TerminalWriteResult.ALREADY_TERMINAL;
    }
    if (!"PENDING".equals(requiredText(attempt, "status"))) {
      throw invalid("Initial-admission attempt is not in its one pending state");
    }

    requireIntentAndHoldMatch(intent, attempt, hold);
    AttemptSnapshot original =
        validateOriginalClaim(claim, protection.originalTuple().canonicalBytes());
    requireProtectionMatchesCurrent(evidence, original);
    lockAdmissionRealm(namespace, holdRequest.canonicalTenantId(), holdRequest.realmId());
    // Lock the current canonical pointer/origin and catalog after the request, tenant/launch, and
    // original owner rows. Recheck the original DB-clock lease after all of these possible waits.
    Record prior = selectPriorOrigin(holdRequest, true);
    Record catalog = lockCatalogRow(intent);
    verifyIntentLaunchCatalog(intent, catalog);
    AttemptSnapshot finalCurrent =
        validateOriginalClaim(claim, protection.originalTuple().canonicalBytes());
    requireProtectionMatchesCurrent(evidence, finalCurrent);

    if (action == TerminalAction.COMMIT) {
      commitPointerAndAttempt(claim, attempt, intent, holdRequest, evidence, prior, catalog);
    } else {
      requireAbortExclusion(holdRequest, intent, prior);
      updateAttemptAborted(claim, attempt, intent, evidence, abortReason, prior);
    }
    return TerminalWriteResult.WRITTEN;
  }

  private WorldCanonicalInitialAdmissionHold.Request beginIntentInTransaction(
      AttemptClaim claim, PreparedIntent prepared) {
    requireWritableReadCommittedOwnerTransaction();
    WorldCanonicalInitialAdmissionHold.Request request = prepared.holdRequest();
    String namespace = workloadNamespace;
    String initialRequestId = request.initialAdmissionRequestId();
    lockRequest(namespace, initialRequestId);
    lockTenantAndLaunch(
        namespace, request.canonicalTenantId(), prepared.launch().controlPlaneRequestId());
    requireActualLaunchAndCatalog(prepared.launch());
    validateOriginalClaim(claim, prepared.launch().originalStartSessionTuple());
    lockAdmissionRealm(namespace, request.canonicalTenantId(), request.realmId());
    validateOriginalClaim(claim, prepared.launch().originalStartSessionTuple());

    Record existing = selectIntent(namespace, initialRequestId, true);
    if (existing != null) {
      requireIntentMatchesPrepared(existing, prepared);
      return WorldCanonicalInitialAdmissionHold.Request.fromStored(
          requiredBytes(existing, "canonical_hold_request_bytes"));
    }
    dsl.execute(
        "INSERT INTO "
            + INTENT_TABLE
            + " (target_namespace, initial_admission_request_id, request_digest, canonical_tenant_id,"
            + " world_slug, realm_id, playable_state_namespace_id, playable_state_scope,"
            + " catalog_visible, catalog_public_production, catalog_revision,"
            + " catalog_creation_request_id, catalog_request_digest, catalog_receipt_digest,"
            + " tenant_association_operation_id, game_session_tenant_id, canonical_game_instance_id,"
            + " game_instance_id, canonical_version_id, runtime_version_id, control_plane_request_id,"
            + " launch_descriptor_id, captured_starting_row_version, current_row_version,"
            + " descriptor_request_digest, descriptor_result_digest, release_attestation_digest,"
            + " catalog_source_intake_operation_id, catalog_source_intake_request_id,"
            + " original_start_session_tuple, canonical_hold_request_bytes, canonical_lifecycle_request_bytes,"
            + " state, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, 'PENDING_HOLD', clock_timestamp(), clock_timestamp())",
        namespace,
        initialRequestId,
        prepared.holdRequest().initialAdmissionRequestDigest(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.expectedCatalogRevision(),
        prepared.launch().catalogCreationRequestId(),
        prepared.catalog().catalogRequestDigest(),
        prepared.catalog().catalogReceiptDigest(),
        prepared.catalog().tenantAssociationOperationId(),
        prepared.launch().gameSessionTenantId(),
        prepared.launch().gameInstanceUuid(),
        prepared.launch().gameInstanceId(),
        request.canonicalVersionId(),
        prepared.launch().launchBindingEvidence().descriptor().versionId(),
        prepared.launch().controlPlaneRequestId(),
        prepared.launch().launchBindingEvidence().descriptor().launchDescriptorId(),
        prepared.launch().capturedStartingRowVersion(),
        prepared.launch().currentRowVersion(),
        prepared.launch().launchBindingEvidence().descriptor().requestDigest(),
        prepared.launch().launchBindingEvidence().descriptor().resultDigest(),
        prepared.launch().launchBindingEvidence().releaseAttestation().evidenceDigest(),
        prepared.catalog().intakeOperationId(),
        prepared.catalog().intakeRequestId(),
        prepared.launch().originalStartSessionTuple(),
        request.canonicalRequestBytes(),
        prepared.lifecycle().request().canonicalBytes());
    Record inserted = selectIntent(namespace, initialRequestId, false);
    if (inserted == null) {
      throw invalid(
          "New canonical initial-admission intent did not read back in its owner transaction");
    }
    requireIntentMatchesPrepared(inserted, prepared);
    validateOriginalClaim(claim, prepared.launch().originalStartSessionTuple());
    return request;
  }

  private CanonicalInitialAdmissionRequest attachWorldHoldInTransaction(
      AttemptClaim claim, WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    requireWritableReadCommittedOwnerTransaction();
    WorldCanonicalInitialAdmissionHold.Request request = hold.request();
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw invalid("World hold request namespace differs from this owner");
    }
    String initialRequestId = request.initialAdmissionRequestId();
    lockRequest(workloadNamespace, initialRequestId);
    lockTenantAndLaunch(
        workloadNamespace, request.canonicalTenantId(), controlRequestForIntent(initialRequestId));
    Record intent = selectIntent(workloadNamespace, initialRequestId, true);
    if (intent == null) {
      throw invalid("World hold has no previously persisted Game Session intent");
    }
    requireIntentRequestMatchesHold(intent, hold);
    validateOriginalClaim(claim, requiredBytes(intent, "original_start_session_tuple"));
    lockAdmissionRealm(workloadNamespace, request.canonicalTenantId(), request.realmId());
    validateOriginalClaim(claim, requiredBytes(intent, "original_start_session_tuple"));

    byte[] exactHoldBytes = hold.canonicalBytes();
    String state = requiredText(intent, "state");
    if ("HOLD_ATTACHED".equals(state) || "TERMINAL".equals(state)) {
      byte[] retainedHold = requiredBytes(intent, "hold_identity_bytes");
      if (!Arrays.equals(retainedHold, exactHoldBytes)) {
        throw invalid("Initial-admission intent already retains a different World hold identity");
      }
      Record existing = selectInitialAttempt(workloadNamespace, initialRequestId, true);
      if (existing == null) {
        throw invalid("Attached World hold has no V28 owner attempt");
      }
      return ownerRequest(existing);
    }
    if (!"PENDING_HOLD".equals(state)
        || optionalBytes(intent, "hold_identity_bytes") != null
        || optionalInstant(intent, "terminal_at") != null) {
      throw invalid("Initial-admission intent is not awaiting its one World hold identity");
    }

    Record existingAttempt = selectInitialAttempt(workloadNamespace, initialRequestId, true);
    if (existingAttempt != null) {
      throw invalid("PENDING_HOLD intent already has an owner attempt");
    }
    dsl.execute(
        "INSERT INTO "
            + ATTEMPT_TABLE
            + " (target_namespace, initial_admission_request_id, request_digest, canonical_tenant_id,"
            + " world_slug, realm_id, playable_state_namespace_id, playable_state_scope,"
            + " canonical_game_instance_id, canonical_version_id, game_session_tenant_id, game_instance_id,"
            + " runtime_version_id, expected_catalog_revision, origin_kind, expected_prior_pointer_version,"
            + " active_lifecycle_epoch, hold_id, hold_fence, hold_binding_digest, status, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',"
            + " clock_timestamp(), clock_timestamp())",
        workloadNamespace,
        initialRequestId,
        request.initialAdmissionRequestDigest(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        requiredLong(intent, "game_session_tenant_id"),
        requiredLong(intent, "game_instance_id"),
        requiredLong(intent, "runtime_version_id"),
        request.expectedCatalogRevision(),
        request.initialAdmissionOrigin().name(),
        request.expectedPriorPointerVersion(),
        request.activeLifecycleEpoch(),
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest());
    int updated =
        dsl.execute(
            "UPDATE "
                + INTENT_TABLE
                + " SET hold_identity_bytes = ?, state = 'HOLD_ATTACHED', updated_at = clock_timestamp()"
                + " WHERE target_namespace = ? AND initial_admission_request_id = ? AND state = 'PENDING_HOLD'"
                + " AND hold_identity_bytes IS NULL AND terminal_at IS NULL",
            exactHoldBytes,
            workloadNamespace,
            initialRequestId);
    if (updated != 1) {
      throw invalid("World hold identity was not attached to exactly one pending intent");
    }
    Record inserted = selectInitialAttempt(workloadNamespace, initialRequestId, false);
    if (inserted == null) {
      throw invalid("New canonical initial-admission owner attempt did not read back");
    }
    requireAttemptMatchesIntent(inserted, intent, hold);
    validateOriginalClaim(claim, requiredBytes(intent, "original_start_session_tuple"));
    return ownerRequest(inserted);
  }

  private void commitPointerAndAttempt(
      AttemptClaim claim,
      Record attempt,
      Record intent,
      WorldCanonicalInitialAdmissionHold.Request holdRequest,
      AccountStartSessionAdmissionProtectionEvidence evidence,
      Record prior,
      Record catalog) {
    requireNoPriorTerminalEvidence(attempt);
    Instant terminalAt = databaseClock();
    long pointerVersion;
    long pointerId;
    UUID priorRequestId = null;
    String priorRequestDigest = null;
    Long priorEventId = null;
    if (holdRequest.initialAdmissionOrigin()
        == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER) {
      requireNoPriorOrigin(holdRequest);
      pointerVersion = 1L;
      pointerId =
          insertOpenPointer(intent, attempt, holdRequest, catalog, terminalAt, pointerVersion);
    } else {
      if (prior == null) {
        throw invalid("Expected canonical never-open CLOSED pointer origin is unavailable");
      }
      requireClosedPriorOrigin(prior, intent, holdRequest);
      pointerVersion = Math.addExact(holdRequest.expectedPriorPointerVersion(), 1L);
      pointerId =
          promoteClosedPointer(
              prior, intent, attempt, holdRequest, catalog, terminalAt, pointerVersion);
      priorRequestId = requiredUuid(prior, "prior_request_id");
      priorRequestDigest = requiredText(prior, "prior_request_digest");
      priorEventId = requiredLong(prior, "prior_audit_event_id");
    }
    long auditEventId =
        insertOpenPointerEvent(intent, attempt, holdRequest, catalog, terminalAt, pointerVersion);
    AttemptSnapshot current =
        validateOriginalClaim(claim, evidence.request().originalPostAuthorizationTupleBytes());
    requireProtectionMatchesCurrent(evidence, current);
    int updated =
        dsl.execute(
            "UPDATE "
                + ATTEMPT_TABLE
                + " SET status = 'COMMITTED', pointer_id = ?, pointer_version = ?, audit_event_id = ?,"
                + " prior_pointer_request_id = ?, prior_pointer_request_digest = ?,"
                + " prior_pointer_audit_event_id = ?, account_protection_evidence_version = 1,"
                + " account_protection_id = ?, account_protection_fence = ?, original_owner_attempt_id = ?,"
                + " original_owner_mutation_id = ?, original_owner_fence = ?,"
                + " account_protection_evidence_bytes = ?, terminal_at = ?, updated_at = ?"
                + " WHERE target_namespace = ? AND initial_admission_request_id = ? AND status = 'PENDING'",
            pointerId,
            pointerVersion,
            auditEventId,
            priorRequestId,
            priorRequestDigest,
            priorEventId,
            evidence.accountProtectionId(),
            evidence.accountProtectionFence(),
            evidence.request().gameSessionOwnerAttemptId(),
            evidence.request().gameSessionOwnerMutationId(),
            evidence.request().gameSessionOwnerFence(),
            evidence.canonicalBytes(),
            terminalAt,
            terminalAt,
            requiredText(attempt, "target_namespace"),
            requiredText(attempt, "initial_admission_request_id"));
    if (updated != 1) {
      throw invalid("Canonical initial-admission attempt was not pending at commit");
    }
    updateIntentTerminal(intent, terminalAt);
  }

  private long insertOpenPointer(
      Record intent,
      Record attempt,
      WorldCanonicalInitialAdmissionHold.Request request,
      Record catalog,
      Instant terminalAt,
      long pointerVersion) {
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO gameplay_admission_pointer (world_slug, world_display_name, realm_slug,"
                + " realm_display_name, tenant_id, game_instance_id, pointer_version, visible,"
                + " requires_character_selection, state_scope, character_creation_policy, last_updated_by,"
                + " last_update_reason, created_at, updated_at, public_production_realm, representation_version,"
                + " target_namespace, canonical_tenant_id, admission_state, realm_id, playable_state_namespace_id,"
                + " canonical_game_instance_id, canonical_version_id, runtime_version_id, initial_admission_request_id,"
                + " initial_admission_request_digest, initial_admission_origin_kind, initial_admission_prior_pointer_version,"
                + " initial_admission_active_epoch, initial_admission_hold_id, initial_admission_hold_fence,"
                + " initial_admission_hold_binding_digest)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'SHARED', ?,"
                + " 'game-session-canonical-initial-admission', 'World-held initial admission', ?, ?, TRUE, 3,"
                + " ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            requiredText(intent, "world_slug"),
            requiredText(catalog, "source_world_display_name"),
            requiredText(catalog, "realm_slug"),
            requiredText(catalog, "realm_display_name"),
            requiredLong(intent, "game_session_tenant_id"),
            requiredLong(intent, "game_instance_id"),
            pointerVersion,
            requiredBoolean(catalog, "visible"),
            requiredText(catalog, "character_creation_policy"),
            terminalAt.atOffset(ZoneOffset.UTC).toLocalDateTime(),
            terminalAt.atOffset(ZoneOffset.UTC).toLocalDateTime(),
            requiredText(intent, "target_namespace"),
            requiredUuid(intent, "canonical_tenant_id"),
            requiredUuid(intent, "realm_id"),
            requiredUuid(intent, "playable_state_namespace_id"),
            requiredUuid(intent, "canonical_game_instance_id"),
            requiredUuid(intent, "canonical_version_id"),
            requiredLong(intent, "runtime_version_id"),
            requiredText(intent, "initial_admission_request_id"),
            requiredText(attempt, "request_digest"),
            request.initialAdmissionOrigin().name(),
            request.expectedPriorPointerVersion(),
            request.activeLifecycleEpoch(),
            requiredUuid(attempt, "hold_id"),
            requiredUuid(attempt, "hold_fence"),
            requiredText(attempt, "hold_binding_digest"));
    return requiredLong(inserted, "id");
  }

  private long promoteClosedPointer(
      Record prior,
      Record intent,
      Record attempt,
      WorldCanonicalInitialAdmissionHold.Request request,
      Record catalog,
      Instant terminalAt,
      long pointerVersion) {
    int updated =
        dsl.execute(
            "UPDATE gameplay_admission_pointer SET representation_version = 3,"
                + " playable_state_namespace_id = ?, admission_state = 'OPEN', tenant_id = ?, game_instance_id = ?,"
                + " pointer_version = ?, world_slug = ?, realm_slug = ?, world_display_name = ?, realm_display_name = ?,"
                + " visible = ?, public_production_realm = TRUE, requires_character_selection = NULL,"
                + " state_scope = 'SHARED', character_creation_policy = ?,"
                + " last_updated_by = 'game-session-canonical-initial-admission',"
                + " last_update_reason = 'World-held initial admission', updated_at = ?,"
                + " canonical_game_instance_id = ?, canonical_version_id = ?, runtime_version_id = ?,"
                + " initial_admission_request_id = ?, initial_admission_request_digest = ?,"
                + " initial_admission_origin_kind = 'EXPECT_CLOSED', initial_admission_prior_pointer_version = ?,"
                + " initial_admission_active_epoch = ?, initial_admission_hold_id = ?, initial_admission_hold_fence = ?,"
                + " initial_admission_hold_binding_digest = ? WHERE id = ? AND representation_version = 2"
                + " AND admission_state = 'CLOSED' AND pointer_version = 1",
            requiredUuid(intent, "playable_state_namespace_id"),
            requiredLong(intent, "game_session_tenant_id"),
            requiredLong(intent, "game_instance_id"),
            pointerVersion,
            requiredText(intent, "world_slug"),
            requiredText(catalog, "realm_slug"),
            requiredText(catalog, "source_world_display_name"),
            requiredText(catalog, "realm_display_name"),
            requiredBoolean(catalog, "visible"),
            requiredText(catalog, "character_creation_policy"),
            terminalAt.atOffset(ZoneOffset.UTC).toLocalDateTime(),
            requiredUuid(intent, "canonical_game_instance_id"),
            requiredUuid(intent, "canonical_version_id"),
            requiredLong(intent, "runtime_version_id"),
            requiredText(intent, "initial_admission_request_id"),
            requiredText(attempt, "request_digest"),
            request.expectedPriorPointerVersion(),
            request.activeLifecycleEpoch(),
            requiredUuid(attempt, "hold_id"),
            requiredUuid(attempt, "hold_fence"),
            requiredText(attempt, "hold_binding_digest"),
            requiredLong(prior, "pointer_id"));
    if (updated != 1) {
      throw invalid("Exact canonical CLOSED pointer was not promoted by the first-OPEN owner");
    }
    return requiredLong(prior, "pointer_id");
  }

  private long insertOpenPointerEvent(
      Record intent,
      Record attempt,
      WorldCanonicalInitialAdmissionHold.Request request,
      Record catalog,
      Instant terminalAt,
      long pointerVersion) {
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO gameplay_admission_pointer_event (world_slug, realm_slug, world_display_name,"
                + " realm_display_name, tenant_id, game_instance_id, pointer_version, visible,"
                + " requires_character_selection, state_scope, character_creation_policy, actor_principal, reason,"
                + " control_plane_request_id, occurred_at, prepared_version_upgrade_id, public_production_realm,"
                + " representation_version, target_namespace, canonical_tenant_id, admission_state, catalog_revision,"
                + " realm_id, playable_state_namespace_id, canonical_game_instance_id, canonical_version_id,"
                + " runtime_version_id, initial_admission_request_id, initial_admission_request_digest,"
                + " initial_admission_origin_kind, initial_admission_prior_pointer_version,"
                + " initial_admission_active_epoch, initial_admission_hold_id, initial_admission_hold_fence,"
                + " initial_admission_hold_binding_digest)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'SHARED', ?,"
                + " 'game-session-canonical-initial-admission', 'World-held initial admission', ?, ?, NULL, TRUE, 3,"
                + " ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            requiredText(intent, "world_slug"),
            requiredText(catalog, "realm_slug"),
            requiredText(catalog, "source_world_display_name"),
            requiredText(catalog, "realm_display_name"),
            requiredLong(intent, "game_session_tenant_id"),
            requiredLong(intent, "game_instance_id"),
            pointerVersion,
            requiredBoolean(catalog, "visible"),
            requiredText(catalog, "character_creation_policy"),
            requiredText(intent, "initial_admission_request_id"),
            terminalAt.atOffset(ZoneOffset.UTC).toLocalDateTime(),
            requiredText(intent, "target_namespace"),
            requiredUuid(intent, "canonical_tenant_id"),
            requiredLong(intent, "catalog_revision"),
            requiredUuid(intent, "realm_id"),
            requiredUuid(intent, "playable_state_namespace_id"),
            requiredUuid(intent, "canonical_game_instance_id"),
            requiredUuid(intent, "canonical_version_id"),
            requiredLong(intent, "runtime_version_id"),
            requiredText(intent, "initial_admission_request_id"),
            requiredText(attempt, "request_digest"),
            request.initialAdmissionOrigin().name(),
            request.expectedPriorPointerVersion(),
            request.activeLifecycleEpoch(),
            requiredUuid(attempt, "hold_id"),
            requiredUuid(attempt, "hold_fence"),
            requiredText(attempt, "hold_binding_digest"));
    return requiredLong(inserted, "id");
  }

  private void updateAttemptAborted(
      AttemptClaim claim,
      Record attempt,
      Record intent,
      AccountStartSessionAdmissionProtectionEvidence evidence,
      String reason,
      Record prior) {
    requireNoPriorTerminalEvidence(attempt);
    UUID priorRequestId = null;
    String priorDigest = null;
    Long priorEventId = null;
    if ("EXPECT_CLOSED".equals(requiredText(attempt, "origin_kind"))) {
      if (prior == null) {
        throw invalid("Definitive abort requires the exact canonical never-open CLOSED origin");
      }
      requireClosedPriorOrigin(
          prior, intent, evidence.request().worldAdmissionHoldIdentity().request());
      priorRequestId = requiredUuid(prior, "prior_request_id");
      priorDigest = requiredText(prior, "prior_request_digest");
      priorEventId = requiredLong(prior, "prior_audit_event_id");
    } else {
      requireNoPriorOrigin(evidence.request().worldAdmissionHoldIdentity().request());
    }
    AttemptSnapshot current =
        validateOriginalClaim(claim, evidence.request().originalPostAuthorizationTupleBytes());
    requireProtectionMatchesCurrent(evidence, current);
    Instant terminalAt = databaseClock();
    int updated =
        dsl.execute(
            "UPDATE "
                + ATTEMPT_TABLE
                + " SET status = 'ABORTED', prior_pointer_request_id = ?, prior_pointer_request_digest = ?,"
                + " prior_pointer_audit_event_id = ?, abort_reason = ?, account_protection_evidence_version = 1,"
                + " account_protection_id = ?, account_protection_fence = ?, original_owner_attempt_id = ?,"
                + " original_owner_mutation_id = ?, original_owner_fence = ?,"
                + " account_protection_evidence_bytes = ?, terminal_at = ?, updated_at = ?"
                + " WHERE target_namespace = ? AND initial_admission_request_id = ? AND status = 'PENDING'",
            priorRequestId,
            priorDigest,
            priorEventId,
            reason,
            evidence.accountProtectionId(),
            evidence.accountProtectionFence(),
            evidence.request().gameSessionOwnerAttemptId(),
            evidence.request().gameSessionOwnerMutationId(),
            evidence.request().gameSessionOwnerFence(),
            evidence.canonicalBytes(),
            terminalAt,
            terminalAt,
            requiredText(attempt, "target_namespace"),
            requiredText(attempt, "initial_admission_request_id"));
    if (updated != 1) {
      throw invalid("Canonical initial-admission attempt was not pending at abort");
    }
    updateIntentTerminal(intent, terminalAt);
  }

  private void updateIntentTerminal(Record intent, Instant terminalAt) {
    int updated =
        dsl.execute(
            "UPDATE "
                + INTENT_TABLE
                + " SET state = 'TERMINAL', terminal_at = ?, updated_at = ?"
                + " WHERE target_namespace = ? AND initial_admission_request_id = ?"
                + " AND state = 'HOLD_ATTACHED' AND hold_identity_bytes IS NOT NULL AND terminal_at IS NULL",
            terminalAt,
            terminalAt,
            requiredText(intent, "target_namespace"),
            requiredText(intent, "initial_admission_request_id"));
    if (updated != 1) {
      throw invalid("Terminal owner result was not attached to exactly one held intent");
    }
  }

  private Optional<OriginalStartSessionAdmissionTerminalResult> readTerminalInTransaction(
      OriginalStartSessionAdmissionTerminalRequest selector) {
    requireReadOnlyRepeatableReadOwnerTransaction();
    AccountStartSessionAdmissionProtectionEvidence requestedEvidence =
        selector.protectionEvidence();
    AccountStartSessionAdmissionProtectionRequest protection = requestedEvidence.request();
    WorldCanonicalInitialAdmissionHold.HoldIdentity hold = protection.worldAdmissionHoldIdentity();
    WorldCanonicalInitialAdmissionHold.Request holdRequest = hold.request();
    String namespace = selector.targetNamespace();
    String initialRequestId = holdRequest.initialAdmissionRequestId();

    Record intent = selectIntent(namespace, initialRequestId, false);
    if (intent == null) {
      if (selectInitialAttempt(namespace, initialRequestId, false) != null) {
        throw invalid("Canonical V28 owner attempt exists without its required immutable intent");
      }
      return Optional.empty();
    }
    Record attempt = selectInitialAttempt(namespace, initialRequestId, false);
    if (attempt == null) {
      if ("PENDING_HOLD".equals(requiredText(intent, "state"))
          && optionalBytes(intent, "hold_identity_bytes") == null) {
        return Optional.empty();
      }
      throw invalid("Attached or terminal intent has no canonical V28 owner attempt");
    }
    requireIntentAndHoldMatch(intent, attempt, hold);
    String status = requiredText(attempt, "status");
    byte[] storedProtection = optionalBytes(attempt, "account_protection_evidence_bytes");
    Integer evidenceVersion = nullableInteger(attempt, "account_protection_evidence_version");
    if ("PENDING".equals(status)) {
      if (storedProtection != null
          || evidenceVersion != null
          || isTerminal(requiredText(intent, "state"))) {
        throw invalid(
            "PENDING initial-admission owner has incomplete or terminal Account evidence");
      }
      return Optional.empty();
    }
    if (!isTerminal(status)
        || storedProtection == null
        || evidenceVersion == null
        || evidenceVersion != 1
        || !Arrays.equals(storedProtection, selector.canonicalBytes())
        || !Arrays.equals(storedProtection, requestedEvidence.canonicalBytes())
        || !"TERMINAL".equals(requiredText(intent, "state"))) {
      throw invalid(
          "Terminal V28 owner result lacks exact complete retained Account protection evidence");
    }
    requireV44EvidenceLinkage(attempt, intent, storedProtection, requestedEvidence);
    requireIntentLaunchCatalogInReadback(intent);
    requireOriginalAttemptHistoricalMatch(attempt, requestedEvidence);

    Instant terminalAt = requiredInstant(attempt, "terminal_at");
    if (terminalAt.getNano() % 1_000 != 0
        || !terminalAt.equals(requiredInstant(intent, "terminal_at"))) {
      throw invalid(
          "Terminal owner and intent timestamps are not the exact stored database instant");
    }
    String digest;
    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome;
    Long pointerVersion = nullableLong(attempt, "pointer_version");
    Long auditEventId = nullableLong(attempt, "audit_event_id");
    if ("COMMITTED".equals(status)) {
      outcome = GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED;
      if (pointerVersion == null || auditEventId == null) {
        throw invalid("COMMITTED terminal owner row has no pointer version and audit event");
      }
      requireCommittedPointerAuditReadback(attempt, intent);
      digest = requiredText(attempt, "commit_proof_digest");
      if (optionalText(attempt, "abort_proof_digest") != null
          || optionalText(attempt, "abort_reason") != null) {
        throw invalid("COMMITTED owner row also carries abort evidence");
      }
    } else {
      outcome = GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED;
      if (pointerVersion != null || auditEventId != null) {
        throw invalid("ABORTED terminal owner row carries commit pointer fields");
      }
      requirePositiveDurableAbortReadback(attempt, intent);
      digest = requiredText(attempt, "abort_proof_digest");
      requireText(requiredText(attempt, "abort_reason"), "persisted abort reason", 500);
      if (optionalText(attempt, "commit_proof_digest") != null) {
        throw invalid("ABORTED owner row also carries commit proof");
      }
    }
    String expectedDigest =
        terminalDigest(
            storedProtection,
            requiredText(attempt, "request_digest"),
            outcome.name(),
            nullableLong(attempt, "pointer_id"),
            pointerVersion,
            auditEventId,
            terminalAt,
            "ABORTED".equals(status) ? requiredText(attempt, "abort_reason") : "");
    if (!expectedDigest.equals(digest)) {
      throw invalid(
          "Stored terminal digest does not rederive from complete immutable owner evidence");
    }
    boolean durableAbort =
        outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED;
    GameSessionCanonicalInitialAdmissionOwnerProof ownerProof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold, outcome, pointerVersion, auditEventId, digest, durableAbort, terminalAt);
    return Optional.of(new OriginalStartSessionAdmissionTerminalResult(selector, ownerProof));
  }

  private void requireCommittedPointerAuditReadback(Record attempt, Record intent) {
    Record row =
        dsl.fetchOne(
            "SELECT p.id AS pointer_id, e.id AS audit_event_id FROM gameplay_admission_pointer p"
                + " JOIN gameplay_admission_pointer_event e ON e.id = ? AND e.representation_version = 3"
                + " JOIN game_session_canonical_realm_catalog c ON c.target_namespace = p.target_namespace"
                + " AND c.canonical_tenant_id = p.canonical_tenant_id AND c.realm_id = p.realm_id"
                + " AND c.world_slug = p.world_slug AND c.catalog_revision = p.catalog_revision"
                + " AND c.playable_state_namespace_id = p.playable_state_namespace_id"
                + " JOIN game_session_canonical_instance_launch l ON l.target_namespace = p.target_namespace"
                + " AND l.game_instance_uuid = p.canonical_game_instance_id AND l.control_plane_request_id = ?"
                + " WHERE p.id = ? AND p.representation_version = 3 AND p.target_namespace = ?"
                + " AND p.canonical_tenant_id = ? AND p.realm_id = ? AND p.world_slug = ?"
                + " AND p.playable_state_namespace_id = ? AND p.state_scope = ?"
                + " AND p.canonical_game_instance_id = ? AND p.canonical_version_id = ?"
                + " AND p.runtime_version_id = ? AND p.tenant_id = ? AND p.game_instance_id = ?"
                + " AND p.pointer_version = ? AND p.catalog_revision = ? AND p.admission_state = 'OPEN'"
                + " AND p.initial_admission_request_id = ? AND p.initial_admission_request_digest = ?"
                + " AND p.initial_admission_origin_kind = ?"
                + " AND p.initial_admission_prior_pointer_version IS NOT DISTINCT FROM ?"
                + " AND p.initial_admission_active_epoch = ? AND p.initial_admission_hold_id = ?"
                + " AND p.initial_admission_hold_fence = ? AND p.initial_admission_hold_binding_digest = ?"
                + " AND p.updated_at = (?::timestamptz AT TIME ZONE 'UTC')"
                + " AND e.target_namespace = p.target_namespace AND e.canonical_tenant_id = p.canonical_tenant_id"
                + " AND e.realm_id = p.realm_id AND e.world_slug = p.world_slug"
                + " AND e.playable_state_namespace_id = p.playable_state_namespace_id"
                + " AND e.canonical_game_instance_id = p.canonical_game_instance_id"
                + " AND e.canonical_version_id = p.canonical_version_id"
                + " AND e.runtime_version_id = p.runtime_version_id AND e.tenant_id = p.tenant_id"
                + " AND e.game_instance_id = p.game_instance_id AND e.pointer_version = p.pointer_version"
                + " AND e.catalog_revision = p.catalog_revision AND e.admission_state = 'OPEN'"
                + " AND e.control_plane_request_id = p.initial_admission_request_id"
                + " AND e.initial_admission_request_id = p.initial_admission_request_id"
                + " AND e.initial_admission_request_digest = p.initial_admission_request_digest"
                + " AND e.initial_admission_origin_kind = p.initial_admission_origin_kind"
                + " AND e.initial_admission_prior_pointer_version IS NOT DISTINCT FROM p.initial_admission_prior_pointer_version"
                + " AND e.initial_admission_active_epoch = p.initial_admission_active_epoch"
                + " AND e.initial_admission_hold_id = p.initial_admission_hold_id"
                + " AND e.initial_admission_hold_fence = p.initial_admission_hold_fence"
                + " AND e.initial_admission_hold_binding_digest = p.initial_admission_hold_binding_digest"
                + " AND e.occurred_at = (?::timestamptz AT TIME ZONE 'UTC')"
                + " AND e.prepared_version_upgrade_id IS NULL"
                + " AND c.visible = p.visible AND c.public_production = p.public_production_realm"
                + " AND c.character_creation_policy = p.character_creation_policy"
                + " AND l.canonical_tenant_id = p.canonical_tenant_id AND l.canonical_realm_id = p.realm_id"
                + " AND l.world_slug = p.world_slug AND l.playable_state_namespace_id = p.playable_state_namespace_id"
                + " AND l.game_session_tenant_id = p.tenant_id AND l.game_instance_id = p.game_instance_id"
                + " AND l.version_id = p.runtime_version_id AND l.public_production IS TRUE"
                + " AND l.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'"
                + " = p.canonical_version_id::text",
            requiredLong(attempt, "audit_event_id"),
            requiredText(intent, "control_plane_request_id"),
            requiredLong(attempt, "pointer_id"),
            requiredText(attempt, "target_namespace"),
            requiredUuid(attempt, "canonical_tenant_id"),
            requiredUuid(attempt, "realm_id"),
            requiredText(attempt, "world_slug"),
            requiredUuid(attempt, "playable_state_namespace_id"),
            requiredText(attempt, "playable_state_scope"),
            requiredUuid(attempt, "canonical_game_instance_id"),
            requiredUuid(attempt, "canonical_version_id"),
            requiredLong(attempt, "runtime_version_id"),
            requiredLong(attempt, "game_session_tenant_id"),
            requiredLong(attempt, "game_instance_id"),
            requiredLong(attempt, "pointer_version"),
            requiredLong(attempt, "expected_catalog_revision"),
            requiredText(attempt, "initial_admission_request_id"),
            requiredText(attempt, "request_digest"),
            requiredText(attempt, "origin_kind"),
            nullableLong(attempt, "expected_prior_pointer_version"),
            requiredLong(attempt, "active_lifecycle_epoch"),
            requiredUuid(attempt, "hold_id"),
            requiredUuid(attempt, "hold_fence"),
            requiredText(attempt, "hold_binding_digest"),
            requiredInstant(attempt, "terminal_at"),
            requiredInstant(attempt, "terminal_at"));
    if (row == null
        || !sameRequiredLongValue(row, "pointer_id", attempt, "pointer_id")
        || !sameRequiredLongValue(row, "audit_event_id", attempt, "audit_event_id")) {
      throw invalid(
          "COMMITTED owner does not independently match its exact V28 pointer and audit event");
    }
    requireCommittedPriorOriginReadback(attempt);
    if (requiredLong(attempt, "pointer_version")
        != ("NO_PRIOR_POINTER".equals(requiredText(attempt, "origin_kind"))
            ? 1L
            : Math.addExact(requiredLong(attempt, "expected_prior_pointer_version"), 1L))) {
      throw invalid("COMMITTED pointer version does not advance its exact origin precondition");
    }
  }

  private void requireCommittedPriorOriginReadback(Record attempt) {
    String originKind = requiredText(attempt, "origin_kind");
    UUID priorRequestUuid = optionalUuid(attempt, "prior_pointer_request_id");
    String priorRequestDigest = optionalText(attempt, "prior_pointer_request_digest");
    Long priorAuditEventId = nullableLong(attempt, "prior_pointer_audit_event_id");
    Long expectedPriorPointerVersion = nullableLong(attempt, "expected_prior_pointer_version");
    if ("NO_PRIOR_POINTER".equals(originKind)) {
      if (priorRequestUuid != null
          || priorRequestDigest != null
          || priorAuditEventId != null
          || expectedPriorPointerVersion != null) {
        throw invalid("COMMITTED NO_PRIOR_POINTER owner retained prior-origin evidence");
      }
      return;
    }
    if (!"EXPECT_CLOSED".equals(originKind)
        || priorRequestUuid == null
        || priorRequestDigest == null
        || priorAuditEventId == null
        || !Long.valueOf(1L).equals(expectedPriorPointerVersion)) {
      throw invalid("COMMITTED EXPECT_CLOSED owner lacks its exact closed-origin identity");
    }
    Record prior =
        dsl.fetchOne(
            "SELECT origin.request_id AS prior_request_id, origin.request_digest AS prior_request_digest,"
                + " origin.audit_event_id AS prior_audit_event_id, origin.catalog_revision AS prior_catalog_revision,"
                + " origin.pointer_version AS prior_pointer_version,"
                + " origin.catalog_creation_request_id AS prior_catalog_creation_request_id,"
                + " event.id AS prior_event_id FROM game_session_canonical_closed_admission_pointer_request origin"
                + " JOIN gameplay_admission_pointer_event event ON event.id = origin.audit_event_id"
                + " AND event.representation_version = 2 AND event.admission_state = 'CLOSED'"
                + " AND event.pointer_version = 1 AND event.target_namespace = origin.target_namespace"
                + " AND event.canonical_tenant_id = origin.canonical_tenant_id AND event.realm_id = origin.realm_id"
                + " AND event.catalog_revision = origin.catalog_revision"
                + " AND event.control_plane_request_id = origin.request_id::text"
                + " AND event.prepared_version_upgrade_id IS NULL"
                + " JOIN game_session_canonical_realm_catalog catalog"
                + " ON catalog.target_namespace = origin.target_namespace"
                + " AND catalog.canonical_tenant_id = origin.canonical_tenant_id"
                + " AND catalog.realm_id = origin.realm_id"
                + " AND catalog.catalog_revision = origin.catalog_revision"
                + " AND catalog.creation_request_id = origin.catalog_creation_request_id"
                + " AND catalog.world_slug = event.world_slug AND catalog.realm_slug = event.realm_slug"
                + " AND catalog.visible IS TRUE AND catalog.public_production IS TRUE"
                + " WHERE origin.target_namespace = ? AND origin.request_id = ?"
                + " AND origin.request_digest = ? AND origin.audit_event_id = ?"
                + " AND origin.canonical_tenant_id = ? AND origin.realm_id = ?"
                + " AND origin.catalog_creation_request_id = ? AND origin.catalog_revision = ?"
                + " AND origin.admission_state = 'CLOSED' AND origin.pointer_version = 1",
            requiredText(attempt, "target_namespace"),
            priorRequestUuid,
            priorRequestDigest,
            priorAuditEventId,
            requiredUuid(attempt, "canonical_tenant_id"),
            requiredUuid(attempt, "realm_id"),
            requiredUuid(attempt, "catalog_creation_request_id"),
            requiredLong(attempt, "expected_catalog_revision"));
    if (prior == null
        || !priorRequestUuid.equals(requiredUuid(prior, "prior_request_id"))
        || !priorRequestDigest.equals(requiredText(prior, "prior_request_digest"))
        || priorAuditEventId.longValue() != requiredLong(prior, "prior_audit_event_id")
        || priorAuditEventId.longValue() != requiredLong(prior, "prior_event_id")
        || requiredLong(prior, "prior_catalog_revision")
            != requiredLong(attempt, "expected_catalog_revision")
        || requiredLong(prior, "prior_pointer_version") != 1L
        || !requiredUuid(prior, "prior_catalog_creation_request_id")
            .equals(requiredUuid(attempt, "catalog_creation_request_id"))) {
      throw invalid(
          "COMMITTED EXPECT_CLOSED owner does not retain its exact immutable CLOSED request and audit evidence");
    }
  }

  private void requirePositiveDurableAbortReadback(Record attempt, Record intent) {
    WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin origin =
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.valueOf(
            requiredText(attempt, "origin_kind"));
    String namespace = requiredText(attempt, "target_namespace");
    String requestId = requiredText(attempt, "initial_admission_request_id");
    if (dsl.fetchExists(
            dsl.selectOne()
                .from("gameplay_admission_pointer")
                .where(
                    "representation_version = 3 AND target_namespace = ? AND initial_admission_request_id = ?",
                    namespace,
                    requestId))
        || dsl.fetchExists(
            dsl.selectOne()
                .from("gameplay_admission_pointer_event")
                .where(
                    "representation_version = 3 AND target_namespace = ? AND initial_admission_request_id = ?",
                    namespace,
                    requestId))) {
      throw invalid("ABORTED owner has a matching first-OPEN pointer or audit event");
    }
    if (origin == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER) {
      boolean conflicts =
          dsl.fetchExists(
                  dsl.selectOne()
                      .from("gameplay_admission_pointer")
                      .where(
                          "realm_id = ? OR (target_namespace = ? AND canonical_tenant_id = ?)"
                              + " OR (world_slug = ? AND realm_slug = ?) OR (tenant_id = ? AND game_instance_id = ?)",
                          requiredUuid(attempt, "realm_id"),
                          namespace,
                          requiredUuid(attempt, "canonical_tenant_id"),
                          requiredText(attempt, "world_slug"),
                          requiredText(intent, "realm_slug"),
                          requiredLong(attempt, "game_session_tenant_id"),
                          requiredLong(attempt, "game_instance_id")))
              || dsl.fetchExists(
                  dsl.selectOne()
                      .from("gameplay_admission_pointer_event")
                      .where(
                          "realm_id = ? OR (target_namespace = ? AND canonical_tenant_id = ?)"
                              + " OR (world_slug = ? AND realm_slug = ?) OR (tenant_id = ? AND game_instance_id = ?)",
                          requiredUuid(attempt, "realm_id"),
                          namespace,
                          requiredUuid(attempt, "canonical_tenant_id"),
                          requiredText(attempt, "world_slug"),
                          requiredText(intent, "realm_slug"),
                          requiredLong(attempt, "game_session_tenant_id"),
                          requiredLong(attempt, "game_instance_id")))
              || dsl.fetchExists(
                  dsl.selectOne()
                      .from("game_session_canonical_closed_admission_pointer_request")
                      .where(
                          "target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ?",
                          namespace,
                          requiredUuid(attempt, "canonical_tenant_id"),
                          requiredUuid(attempt, "realm_id")))
              || dsl.fetchExists(
                  dsl.selectOne()
                      .from("gameplay_initial_admission_bind_catalog")
                      .where(
                          "tenant_id = ? AND (realm_id = ? OR (world_slug = ? AND realm_slug = ?))",
                          requiredLong(attempt, "game_session_tenant_id"),
                          requiredUuid(attempt, "realm_id"),
                          requiredText(attempt, "world_slug"),
                          requiredText(intent, "realm_slug")))
              || dsl.fetchExists(
                  dsl.selectOne()
                      .from("gameplay_initial_admission_bind_attempt")
                      .where(
                          "tenant_id = ? AND (realm_id = ? OR game_instance_id = ?)",
                          requiredLong(attempt, "game_session_tenant_id"),
                          requiredUuid(attempt, "realm_id"),
                          requiredLong(attempt, "game_instance_id")));
      if (conflicts) {
        throw invalid(
            "NO_PRIOR_POINTER abort is not positively durable while a competing origin exists");
      }
      return;
    }
    Record prior =
        selectPriorOrigin(
            WorldCanonicalInitialAdmissionHold.Request.fromStored(
                requiredBytes(intent, "canonical_hold_request_bytes")),
            false);
    if (prior == null) {
      throw invalid(
          "ABORTED EXPECT_CLOSED owner lacks the exact canonical never-open CLOSED origin");
    }
    requireClosedPriorOrigin(
        prior,
        intent,
        WorldCanonicalInitialAdmissionHold.Request.fromStored(
            requiredBytes(intent, "canonical_hold_request_bytes")));
  }

  private void requireV44EvidenceLinkage(
      Record attempt,
      Record intent,
      byte[] evidenceBytes,
      AccountStartSessionAdmissionProtectionEvidence evidence) {
    if (!Arrays.equals(requiredBytes(attempt, "account_protection_evidence_bytes"), evidenceBytes)
        || !Integer.valueOf(1)
            .equals(nullableInteger(attempt, "account_protection_evidence_version"))
        || !evidence.accountProtectionId().equals(requiredUuid(attempt, "account_protection_id"))
        || evidence.accountProtectionFence() != requiredLong(attempt, "account_protection_fence")
        || !evidence
            .request()
            .gameSessionOwnerAttemptId()
            .equals(requiredUuid(attempt, "original_owner_attempt_id"))
        || !evidence
            .request()
            .gameSessionOwnerMutationId()
            .equals(requiredUuid(attempt, "original_owner_mutation_id"))
        || evidence.request().gameSessionOwnerFence()
            != requiredLong(attempt, "original_owner_fence")
        || !requiredText(intent, "control_plane_request_id")
            .equals(evidence.request().originalTuple().controlPlaneRequestId())
        || !Arrays.equals(
            requiredBytes(intent, "original_start_session_tuple"),
            evidence.request().originalPostAuthorizationTupleBytes())) {
      throw invalid(
          "V44 complete Account evidence is not bound to the exact V28 intent and V35 original owner");
    }
  }

  private void requireIntentLaunchCatalogInReadback(Record intent) {
    Record launch =
        selectLaunchWithBinding(
            requiredText(intent, "target_namespace"),
            requiredText(intent, "control_plane_request_id"),
            false);
    Record catalog =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_realm_catalog WHERE target_namespace = ?"
                + " AND canonical_tenant_id = ? AND realm_id = ?",
            requiredText(intent, "target_namespace"),
            requiredUuid(intent, "canonical_tenant_id"),
            requiredUuid(intent, "realm_id"));
    if (launch == null || catalog == null) {
      throw invalid("Terminal intent no longer has its exact retained launch/catalog records");
    }
    requireIntentMatchesLaunchAndCatalog(intent, launch, catalog);
  }

  private void requireActualLaunchAndCatalog(
      GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch launch) {
    Record actual =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_instance_launch WHERE target_namespace = ?"
                + " AND control_plane_request_id = ? FOR UPDATE",
            workloadNamespace,
            launch.controlPlaneRequestId());
    Record catalogRow =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_realm_catalog WHERE target_namespace = ?"
                + " AND canonical_tenant_id = ? AND realm_id = ? FOR UPDATE",
            workloadNamespace,
            launch.canonicalTenantId(),
            launch.canonicalRealmId());
    CanonicalRealmCatalog catalog =
        catalogRepository
            .readByCanonicalTenantIdInOwnerTransaction(launch.canonicalTenantId())
            .orElseThrow(() -> invalid("Exact source-backed launch catalog is unavailable"));
    if (actual == null || catalogRow == null) {
      throw invalid(
          "Exact canonical launch or catalog row is unavailable in the owner transaction");
    }
    requireLaunchMatches(launch, actual);
    requireCatalogMatches(launch, catalogRow, catalog);
  }

  private Record lockCatalogRow(Record intent) {
    Record catalog =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_realm_catalog WHERE target_namespace = ?"
                + " AND canonical_tenant_id = ? AND realm_id = ? FOR UPDATE",
            requiredText(intent, "target_namespace"),
            requiredUuid(intent, "canonical_tenant_id"),
            requiredUuid(intent, "realm_id"));
    if (catalog == null) {
      throw invalid("Exact initial-admission source-backed catalog row is unavailable");
    }
    return catalog;
  }

  private void verifyIntentLaunchCatalog(Record intent, Record catalog) {
    Record launch =
        selectLaunchWithBinding(
            requiredText(intent, "target_namespace"),
            requiredText(intent, "control_plane_request_id"),
            true);
    if (launch == null) {
      throw invalid("Exact canonical launch association is unavailable at terminal write");
    }
    requireIntentMatchesLaunchAndCatalog(intent, launch, catalog);
  }

  private void requireIntentMatchesLaunchAndCatalog(Record intent, Record launch, Record catalog) {
    if (!requiredText(intent, "target_namespace").equals(requiredText(launch, "target_namespace"))
        || !requiredText(intent, "control_plane_request_id")
            .equals(requiredText(launch, "control_plane_request_id"))
        || !Arrays.equals(
            requiredBytes(launch, "original_start_session_tuple"),
            requiredBytes(intent, "original_start_session_tuple"))
        || !requiredUuid(intent, "canonical_tenant_id")
            .equals(requiredUuid(launch, "canonical_tenant_id"))
        || !requiredUuid(intent, "realm_id").equals(requiredUuid(launch, "canonical_realm_id"))
        || !requiredText(intent, "world_slug").equals(requiredText(launch, "world_slug"))
        || !requiredUuid(intent, "playable_state_namespace_id")
            .equals(requiredUuid(launch, "playable_state_namespace_id"))
        || !requiredUuid(intent, "canonical_game_instance_id")
            .equals(requiredUuid(launch, "game_instance_uuid"))
        || !requiredText(intent, "playable_state_scope")
            .equals(requiredText(launch, "playable_state_scope"))
        || !requiredBoolean(launch, "public_production")
        || requiredLong(intent, "game_session_tenant_id")
            != requiredLong(launch, "game_session_tenant_id")
        || requiredLong(intent, "game_instance_id") != requiredLong(launch, "game_instance_id")
        || requiredLong(intent, "runtime_version_id") != requiredLong(launch, "version_id")
        || !requiredUuid(intent, "tenant_association_operation_id")
            .equals(requiredUuid(launch, "tenant_association_operation_id"))
        || !requiredText(intent, "launch_descriptor_id")
            .equals(requiredText(launch, "launch_descriptor_id"))
        || requiredLong(intent, "captured_starting_row_version")
            != requiredLong(launch, "captured_starting_row_version")
        || !requiredText(intent, "descriptor_request_digest")
            .equals(requiredText(launch, "launch_descriptor_request_digest"))
        || !requiredText(intent, "descriptor_result_digest")
            .equals(requiredText(launch, "launch_descriptor_result_digest"))
        || !requiredText(intent, "release_attestation_digest")
            .equals(requiredText(launch, "launch_release_attestation_digest"))
        || !requiredUuid(intent, "catalog_source_intake_operation_id")
            .equals(requiredUuid(launch, "catalog_source_intake_operation_id"))
        || !requiredUuid(intent, "catalog_source_intake_request_id")
            .equals(requiredUuid(launch, "catalog_source_intake_request_id"))
        || !requiredUuid(intent, "catalog_creation_request_id")
            .equals(requiredUuid(launch, "catalog_creation_request_id"))
        || requiredLong(intent, "catalog_revision") != requiredLong(launch, "catalog_revision")
        || !requiredUuid(intent, "canonical_tenant_id")
            .equals(requiredUuid(catalog, "canonical_tenant_id"))
        || !requiredUuid(intent, "realm_id").equals(requiredUuid(catalog, "realm_id"))
        || requiredLong(intent, "catalog_revision") != requiredLong(catalog, "catalog_revision")
        || !requiredUuid(intent, "catalog_creation_request_id")
            .equals(requiredUuid(catalog, "creation_request_id"))
        || !requiredUuid(intent, "catalog_source_intake_operation_id")
            .equals(requiredUuid(catalog, "source_intake_operation_id"))
        || !requiredUuid(intent, "catalog_source_intake_request_id")
            .equals(requiredUuid(catalog, "source_intake_request_id"))
        || !requiredText(intent, "catalog_request_digest")
            .equals(requiredText(catalog, "request_digest"))
        || !requiredText(intent, "catalog_receipt_digest")
            .equals(requiredText(catalog, "receipt_digest"))
        || !requiredBoolean(catalog, "visible")
        || !requiredBoolean(catalog, "public_production")
        || !"SHARED".equals(requiredText(catalog, "state_scope"))) {
      throw invalid("Retained initial-admission intent differs from its actual V43 launch/catalog");
    }
  }

  private void requireLaunchMatches(
      GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch expected,
      Record actual) {
    if (!expected.targetNamespace().equals(requiredText(actual, "target_namespace"))
        || !expected
            .controlPlaneRequestId()
            .equals(requiredText(actual, "control_plane_request_id"))
        || !expected.canonicalTenantId().equals(requiredUuid(actual, "canonical_tenant_id"))
        || !expected.gameInstanceUuid().equals(requiredUuid(actual, "game_instance_uuid"))
        || !expected.canonicalRealmId().equals(requiredUuid(actual, "canonical_realm_id"))
        || !expected
            .playableStateNamespaceId()
            .equals(requiredUuid(actual, "playable_state_namespace_id"))
        || expected.gameSessionTenantId() != requiredLong(actual, "game_session_tenant_id")
        || expected.gameInstanceId() != requiredLong(actual, "game_instance_id")
        || expected.catalogRevision() != requiredLong(actual, "catalog_revision")
        || !Arrays.equals(
            expected.originalStartSessionTuple(),
            requiredBytes(actual, "original_start_session_tuple"))) {
      throw invalid("Supplied canonical launch differs from its actual immutable V43 row");
    }
  }

  private void requireCatalogMatches(
      GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch launch,
      Record actual,
      CanonicalRealmCatalog typed) {
    if (!typed.canonicalTenantId().equals(requiredUuid(actual, "canonical_tenant_id"))
        || !typed.realmId().equals(requiredUuid(actual, "realm_id"))
        || !typed.worldSlug().equals(requiredText(actual, "world_slug"))
        || !typed
            .playableStateNamespaceId()
            .equals(requiredUuid(actual, "playable_state_namespace_id"))
        || !typed.catalogRequestId().equals(requiredUuid(actual, "creation_request_id"))
        || !typed.intakeOperationId().equals(requiredUuid(actual, "source_intake_operation_id"))
        || !typed.intakeRequestId().equals(requiredUuid(actual, "source_intake_request_id"))
        || typed.catalogRevision() != requiredLong(actual, "catalog_revision")
        || launch.catalogRevision() != requiredLong(actual, "catalog_revision")
        || !requiredBoolean(actual, "visible")
        || !requiredBoolean(actual, "public_production")
        || !"SHARED".equals(requiredText(actual, "state_scope"))) {
      throw invalid("Source-backed canonical catalog differs from exact launch catalog identity");
    }
  }

  private void requireIntentMatchesPrepared(Record intent, PreparedIntent prepared) {
    WorldCanonicalInitialAdmissionHold.Request stored =
        WorldCanonicalInitialAdmissionHold.Request.fromStored(
            requiredBytes(intent, "canonical_hold_request_bytes"));
    var launch = prepared.launch();
    var catalog = prepared.catalog();
    if (!Arrays.equals(
            stored.canonicalRequestBytes(), prepared.holdRequest().canonicalRequestBytes())
        || !requiredText(intent, "target_namespace").equals(workloadNamespace)
        || !requiredText(intent, "initial_admission_request_id")
            .equals(prepared.holdRequest().initialAdmissionRequestId())
        || !requiredText(intent, "request_digest")
            .equals(prepared.holdRequest().initialAdmissionRequestDigest())
        || !requiredUuid(intent, "canonical_tenant_id").equals(launch.canonicalTenantId())
        || !requiredText(intent, "world_slug").equals(catalog.worldSlug())
        || !requiredUuid(intent, "realm_id").equals(launch.canonicalRealmId())
        || !requiredUuid(intent, "playable_state_namespace_id")
            .equals(launch.playableStateNamespaceId())
        || !requiredText(intent, "playable_state_scope").equals(launch.playableStateScope().name())
        || !requiredBoolean(intent, "catalog_visible")
        || !requiredBoolean(intent, "catalog_public_production")
        || requiredLong(intent, "catalog_revision") != launch.catalogRevision()
        || !requiredUuid(intent, "catalog_creation_request_id")
            .equals(launch.catalogCreationRequestId())
        || !requiredText(intent, "catalog_request_digest").equals(catalog.catalogRequestDigest())
        || !requiredText(intent, "catalog_receipt_digest").equals(catalog.catalogReceiptDigest())
        || !requiredUuid(intent, "tenant_association_operation_id")
            .equals(catalog.tenantAssociationOperationId())
        || requiredLong(intent, "game_session_tenant_id") != launch.gameSessionTenantId()
        || !requiredUuid(intent, "canonical_game_instance_id").equals(launch.gameInstanceUuid())
        || requiredLong(intent, "game_instance_id") != launch.gameInstanceId()
        || !requiredUuid(intent, "canonical_version_id")
            .equals(launch.launchBindingEvidence().releaseAttestation().canonicalVersionId())
        || requiredLong(intent, "runtime_version_id")
            != launch.launchBindingEvidence().descriptor().versionId()
        || !requiredText(intent, "control_plane_request_id").equals(launch.controlPlaneRequestId())
        || !requiredText(intent, "launch_descriptor_id")
            .equals(launch.launchBindingEvidence().descriptor().launchDescriptorId())
        || requiredLong(intent, "captured_starting_row_version")
            != launch.capturedStartingRowVersion()
        || requiredLong(intent, "current_row_version") != launch.currentRowVersion()
        || !requiredText(intent, "descriptor_request_digest")
            .equals(launch.launchBindingEvidence().descriptor().requestDigest())
        || !requiredText(intent, "descriptor_result_digest")
            .equals(launch.launchBindingEvidence().descriptor().resultDigest())
        || !requiredText(intent, "release_attestation_digest")
            .equals(launch.launchBindingEvidence().releaseAttestation().evidenceDigest())
        || !Arrays.equals(
            requiredBytes(intent, "canonical_lifecycle_request_bytes"),
            prepared.lifecycle().request().canonicalBytes())
        || !Arrays.equals(
            requiredBytes(intent, "original_start_session_tuple"),
            prepared.launch().originalStartSessionTuple())
        || !requiredUuid(intent, "catalog_source_intake_operation_id")
            .equals(prepared.catalog().intakeOperationId())
        || !requiredUuid(intent, "catalog_source_intake_request_id")
            .equals(catalog.intakeRequestId())) {
      throw invalid(
          "Initial-admission request ID is already bound to different exact intent bytes");
    }
  }

  private void requireIntentRequestMatchesHold(
      Record intent, WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    if (!Arrays.equals(
            requiredBytes(intent, "canonical_hold_request_bytes"), hold.canonicalRequestBytes())
        || !Arrays.equals(
            requiredBytes(intent, "original_start_session_tuple"),
            requiredBytes(intent, "launch_original_start_session_tuple"))
        || !requiredText(intent, "target_namespace").equals(hold.request().targetNamespace())
        || !requiredText(intent, "initial_admission_request_id")
            .equals(hold.request().initialAdmissionRequestId())) {
      throw invalid("World hold response differs from the exact previously persisted request");
    }
  }

  private void requireIntentAndHoldMatch(
      Record intent, Record attempt, WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    requireIntentRequestMatchesHold(intent, hold);
    if (!Arrays.equals(requiredBytes(intent, "hold_identity_bytes"), hold.canonicalBytes())
        || !"HOLD_ATTACHED".equals(requiredText(intent, "state"))
            && !"TERMINAL".equals(requiredText(intent, "state"))) {
      throw invalid("Intent does not retain the exact acquired World hold identity");
    }
    requireAttemptMatchesIntent(attempt, intent, hold);
  }

  private void requireAttemptMatchesIntent(
      Record attempt, Record intent, WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    WorldCanonicalInitialAdmissionHold.Request request = hold.request();
    if (!requiredText(attempt, "target_namespace").equals(requiredText(intent, "target_namespace"))
        || !requiredText(attempt, "initial_admission_request_id")
            .equals(requiredText(intent, "initial_admission_request_id"))
        || !requiredText(attempt, "request_digest").equals(request.initialAdmissionRequestDigest())
        || !requiredUuid(attempt, "canonical_tenant_id").equals(request.canonicalTenantId())
        || !requiredUuid(attempt, "realm_id").equals(request.realmId())
        || !requiredText(attempt, "world_slug").equals(request.worldSlug())
        || !requiredUuid(attempt, "playable_state_namespace_id")
            .equals(request.playableStateNamespaceId())
        || !requiredUuid(attempt, "canonical_game_instance_id")
            .equals(request.canonicalGameInstanceId())
        || !requiredUuid(attempt, "canonical_version_id").equals(request.canonicalVersionId())
        || requiredLong(attempt, "game_session_tenant_id")
            != requiredLong(intent, "game_session_tenant_id")
        || requiredLong(attempt, "game_instance_id") != requiredLong(intent, "game_instance_id")
        || requiredLong(attempt, "runtime_version_id") != requiredLong(intent, "runtime_version_id")
        || requiredLong(attempt, "expected_catalog_revision") != request.expectedCatalogRevision()
        || !requiredText(attempt, "origin_kind").equals(request.initialAdmissionOrigin().name())
        || !Objects.equals(
            nullableLong(attempt, "expected_prior_pointer_version"),
            request.expectedPriorPointerVersion())
        || requiredLong(attempt, "active_lifecycle_epoch") != request.activeLifecycleEpoch()
        || !requiredUuid(attempt, "hold_id").equals(hold.holdId())
        || !requiredUuid(attempt, "hold_fence").equals(hold.holdFence())
        || !requiredText(attempt, "hold_binding_digest").equals(hold.holdBindingDigest())) {
      throw invalid("V28 attempt does not match its exact V28 intent and World hold identity");
    }
  }

  private void requireProtectionMatchesCurrent(
      AccountStartSessionAdmissionProtectionEvidence evidence, AttemptSnapshot current) {
    AccountStartSessionAdmissionProtectionRequest request = evidence.request();
    if (!Arrays.equals(
            request.originalPostAuthorizationTupleBytes(),
            current.postAuthorizationExecutionTuple())
        || !Arrays.equals(
            request.accountRedemptionProjectionBytes(), current.accountRedemptionProjection())
        || !request.gameSessionOwnerAttemptId().equals(current.ownerAttemptId())
        || !request.gameSessionOwnerMutationId().equals(current.ownerMutationId())
        || request.gameSessionOwnerFence() != current.ownerFence()
        || !request.originalLeaseExpiresAt().equals(current.leaseExpiresAt())
        || !request.originalTuple().controlPlaneRequestId().equals(current.controlPlaneRequestId())
        || !workloadNamespace.equals(current.targetNamespace())) {
      throw invalid(
          "Complete Account protection evidence differs from the live original V35 claim/projection/lease");
    }
  }

  private AttemptSnapshot validateOriginalClaim(AttemptClaim claim, byte[] expectedTuple) {
    if (!workloadNamespace.equals(claim.targetNamespace())) {
      throw invalid("Original StartSession claim namespace differs from this owner");
    }
    AttemptSnapshot claimSnapshot = originalAttemptRepository.validateCurrentClaim(claim);
    if (!Arrays.equals(expectedTuple, claimSnapshot.postAuthorizationExecutionTuple())) {
      throw invalid("Original StartSession claim is not bound to the exact canonical launch tuple");
    }
    OriginalStartSessionCurrentAttemptEvidence.Request currentRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            UUID.randomUUID(),
            workloadNamespace,
            expectedTuple,
            claim.ownerAttemptId(),
            claim.ownerMutationId(),
            claim.ownerFence());
    AttemptSnapshot current = originalAttemptRepository.readCurrentOriginalAttempt(currentRequest);
    if (!claimSnapshot.ownerAttemptId().equals(current.ownerAttemptId())
        || !claimSnapshot.ownerMutationId().equals(current.ownerMutationId())
        || claimSnapshot.ownerFence() != current.ownerFence()
        || !claimSnapshot.leaseExpiresAt().equals(current.leaseExpiresAt())) {
      throw invalid(
          "Current original StartSession attempt changed while admission evidence was read");
    }
    return current;
  }

  private PreparedIntent prepareIntent(IntentInput input) {
    var launch = input.launch();
    var lifecycle = input.lifecycle();
    requireText(input.initialAdmissionRequestId(), "initialAdmissionRequestId", 120);
    if (!workloadNamespace.equals(launch.targetNamespace())
        || !workloadNamespace.equals(lifecycle.request().targetNamespace())
        || !"ACTIVE".equals(lifecycle.lifecycleStatus())
        || lifecycle.lifecycleEpoch() <= 0
        || !launch.canonicalTenantId().equals(lifecycle.request().canonicalTenantId())
        || !launch.gameInstanceUuid().equals(lifecycle.request().canonicalGameInstanceId())
        || !launch.playableStateNamespaceId().equals(lifecycle.request().playableStateNamespaceId())
        || !launch.controlPlaneRequestId().equals(lifecycle.request().controlPlaneRequestId())
        || !launch.launchBindingEvidence().equals(lifecycle.launchBinding())
        || !lifecycle
            .request()
            .canonicalVersionId()
            .equals(launch.launchBindingEvidence().releaseAttestation().canonicalVersionId())
        || !lifecycle
            .request()
            .expectedDescriptorRequestDigest()
            .equals(launch.launchBindingEvidence().descriptor().requestDigest())
        || !lifecycle
            .request()
            .expectedDescriptorResultDigest()
            .equals(launch.launchBindingEvidence().descriptor().resultDigest())
        || !lifecycle
            .request()
            .expectedReleaseAttestationDigest()
            .equals(launch.launchBindingEvidence().releaseAttestation().evidenceDigest())) {
      throw invalid(
          "World ACTIVE lifecycle evidence differs from the exact source-backed launch binding");
    }
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(launch.originalStartSessionTuple());
    if (!tuple.controlPlaneRequestId().equals(launch.controlPlaneRequestId())) {
      throw invalid(
          "Canonical launch original tuple differs from its control-plane request identity");
    }
    if (input.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
            && input.expectedPriorPointerVersion() != null
        || input.originKind() == CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED
            && (input.expectedPriorPointerVersion() == null
                || input.expectedPriorPointerVersion() != 1L)) {
      throw invalid(
          "Initial-admission origin must be NO_PRIOR_POINTER or exact never-open version-1 CLOSED");
    }
    CanonicalRealmCatalog catalog = launch.canonicalRealmCatalog();
    UUID canonicalVersionId =
        launch.launchBindingEvidence().releaseAttestation().canonicalVersionId();
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            workloadNamespace,
            launch.canonicalTenantId(),
            catalog.worldSlug(),
            launch.canonicalRealmId(),
            launch.playableStateNamespaceId(),
            launch.playableStateScope().name(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            lifecycle.lifecycleEpoch(),
            launch.catalogRevision(),
            input.originKind(),
            input.expectedPriorPointerVersion(),
            input.initialAdmissionRequestId());
    WorldCanonicalInitialAdmissionHold.Request holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            workloadNamespace,
            launch.canonicalTenantId(),
            catalog.worldSlug(),
            launch.canonicalRealmId(),
            launch.playableStateNamespaceId(),
            launch.playableStateScope().name(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            lifecycle.lifecycleEpoch(),
            input.initialAdmissionRequestId(),
            digest,
            input.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
                ? WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER
                : WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED,
            launch.catalogRevision(),
            input.expectedPriorPointerVersion());
    return new PreparedIntent(launch, lifecycle, catalog, holdRequest);
  }

  private WorldCanonicalInitialAdmissionHold.Request readIntentRequest(PreparedIntent prepared) {
    requireReadOnlyRepeatableReadOwnerTransaction();
    Record intent =
        selectIntent(workloadNamespace, prepared.holdRequest().initialAdmissionRequestId(), false);
    if (intent == null) {
      throw invalid("Committed initial-admission intent is missing on independent readback");
    }
    requireIntentMatchesPrepared(intent, prepared);
    return WorldCanonicalInitialAdmissionHold.Request.fromStored(
        requiredBytes(intent, "canonical_hold_request_bytes"));
  }

  private CanonicalInitialAdmissionRequest ownerRequest(Record attempt) {
    return new CanonicalInitialAdmissionRequest(
        requiredText(attempt, "target_namespace"),
        requiredUuid(attempt, "canonical_tenant_id"),
        requiredText(attempt, "world_slug"),
        requiredUuid(attempt, "realm_id"),
        requiredUuid(attempt, "playable_state_namespace_id"),
        requiredText(attempt, "playable_state_scope"),
        requiredUuid(attempt, "canonical_game_instance_id"),
        requiredUuid(attempt, "canonical_version_id"),
        requiredLong(attempt, "active_lifecycle_epoch"),
        requiredLong(attempt, "expected_catalog_revision"),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(requiredText(attempt, "origin_kind")),
        nullableLong(attempt, "expected_prior_pointer_version"),
        requiredText(attempt, "initial_admission_request_id"),
        requiredText(attempt, "request_digest"),
        requiredUuid(attempt, "hold_id"),
        requiredUuid(attempt, "hold_fence"),
        requiredText(attempt, "hold_binding_digest"));
  }

  private Record selectPriorOrigin(
      WorldCanonicalInitialAdmissionHold.Request request, boolean forUpdate) {
    if (request.initialAdmissionOrigin()
        != WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED) {
      return null;
    }
    return dsl.fetchOne(
        "SELECT p.id AS pointer_id, p.created_at AS pointer_created_at, origin.request_id AS prior_request_id,"
            + " origin.request_digest AS prior_request_digest, origin.audit_event_id AS prior_audit_event_id,"
            + " origin.catalog_revision AS prior_catalog_revision, origin.pointer_version AS prior_pointer_version,"
            + " origin.catalog_creation_request_id AS prior_catalog_creation_request_id"
            + " FROM gameplay_admission_pointer p JOIN game_session_canonical_closed_admission_pointer_request origin"
            + " ON origin.target_namespace = p.target_namespace AND origin.canonical_tenant_id = p.canonical_tenant_id"
            + " AND origin.realm_id = p.realm_id JOIN gameplay_admission_pointer_event event"
            + " ON event.id = origin.audit_event_id AND event.representation_version = 2"
            + " AND event.admission_state = 'CLOSED' AND event.pointer_version = 1"
            + " AND event.target_namespace = origin.target_namespace"
            + " AND event.canonical_tenant_id = origin.canonical_tenant_id AND event.realm_id = origin.realm_id"
            + " AND event.catalog_revision = origin.catalog_revision"
            + " AND event.control_plane_request_id = origin.request_id::text"
            + " WHERE p.representation_version = 2 AND p.admission_state = 'CLOSED'"
            + " AND p.pointer_version = ? AND p.catalog_revision = ?"
            + " AND p.target_namespace = ? AND p.canonical_tenant_id = ? AND p.realm_id = ?"
            + (forUpdate ? " FOR UPDATE OF p" : ""),
        request.expectedPriorPointerVersion(),
        request.expectedCatalogRevision(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.realmId());
  }

  private void requireClosedPriorOrigin(
      Record prior, Record intent, WorldCanonicalInitialAdmissionHold.Request request) {
    if (prior == null
        || request.initialAdmissionOrigin()
            != WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED
        || !Long.valueOf(1L).equals(requiredLong(prior, "prior_pointer_version"))
        || requiredLong(prior, "prior_catalog_revision") != request.expectedCatalogRevision()
        || !requiredUuid(prior, "prior_catalog_creation_request_id")
            .equals(requiredUuid(intent, "catalog_creation_request_id"))) {
      throw invalid("Origin is not the exact canonical never-open version-1 CLOSED pointer");
    }
  }

  private void requireNoPriorOrigin(WorldCanonicalInitialAdmissionHold.Request request) {
    if (request.initialAdmissionOrigin()
        != WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER) {
      return;
    }
    if (dsl.fetchExists(
            dsl.selectOne()
                .from("gameplay_admission_pointer")
                .where(
                    "realm_id = ? OR (target_namespace = ? AND canonical_tenant_id = ?)"
                        + " OR (world_slug = ? AND realm_slug = ?) OR (tenant_id = ? AND game_instance_id = ?)",
                    request.realmId(),
                    request.targetNamespace(),
                    request.canonicalTenantId(),
                    request.worldSlug(),
                    requiredTextByRealm(request, "realm_slug"),
                    gameSessionTenantId(request),
                    gameInstanceId(request)))
        || dsl.fetchExists(
            dsl.selectOne()
                .from("gameplay_admission_pointer_event")
                .where(
                    "realm_id = ? OR (target_namespace = ? AND canonical_tenant_id = ?)"
                        + " OR (world_slug = ? AND realm_slug = ?) OR (tenant_id = ? AND game_instance_id = ?)",
                    request.realmId(),
                    request.targetNamespace(),
                    request.canonicalTenantId(),
                    request.worldSlug(),
                    requiredTextByRealm(request, "realm_slug"),
                    gameSessionTenantId(request),
                    gameInstanceId(request)))
        || dsl.fetchExists(
            dsl.selectOne()
                .from("game_session_canonical_closed_admission_pointer_request")
                .where(
                    "target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ?",
                    request.targetNamespace(),
                    request.canonicalTenantId(),
                    request.realmId()))
        || dsl.fetchExists(
            dsl.selectOne()
                .from("gameplay_initial_admission_bind_catalog")
                .where(
                    "tenant_id = ? AND (realm_id = ? OR (world_slug = ? AND realm_slug = ?))",
                    gameSessionTenantId(request),
                    request.realmId(),
                    request.worldSlug(),
                    requiredTextByRealm(request, "realm_slug")))
        || dsl.fetchExists(
            dsl.selectOne()
                .from("gameplay_initial_admission_bind_attempt")
                .where(
                    "tenant_id = ? AND (realm_id = ? OR game_instance_id = ?)",
                    gameSessionTenantId(request),
                    request.realmId(),
                    gameInstanceId(request)))) {
      throw invalid("NO_PRIOR_POINTER conflicts with retained canonical admission evidence");
    }
  }

  private String requiredTextByRealm(
      WorldCanonicalInitialAdmissionHold.Request request, String field) {
    Record row =
        dsl.fetchOne(
            "SELECT realm_slug FROM game_session_canonical_realm_catalog WHERE target_namespace = ?"
                + " AND canonical_tenant_id = ? AND realm_id = ?",
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId());
    return requiredText(row, field);
  }

  private long gameSessionTenantId(WorldCanonicalInitialAdmissionHold.Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT game_session_tenant_id FROM game_session_canonical_initial_admission_intent"
                + " WHERE target_namespace = ? AND initial_admission_request_id = ?",
            request.targetNamespace(),
            request.initialAdmissionRequestId());
    return requiredLong(row, "game_session_tenant_id");
  }

  private long gameInstanceId(WorldCanonicalInitialAdmissionHold.Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT game_instance_id FROM game_session_canonical_initial_admission_intent"
                + " WHERE target_namespace = ? AND initial_admission_request_id = ?",
            request.targetNamespace(),
            request.initialAdmissionRequestId());
    return requiredLong(row, "game_instance_id");
  }

  private void requireAbortExclusion(
      WorldCanonicalInitialAdmissionHold.Request request, Record intent, Record prior) {
    if (request.initialAdmissionOrigin()
        == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER) {
      requireNoPriorOrigin(request);
    } else {
      requireClosedPriorOrigin(prior, intent, request);
    }
  }

  private void requireNoPriorTerminalEvidence(Record attempt) {
    if (optionalLong(attempt, "pointer_id") != null
        || optionalLong(attempt, "pointer_version") != null
        || optionalLong(attempt, "audit_event_id") != null
        || optionalInstant(attempt, "terminal_at") != null
        || optionalText(attempt, "commit_proof_digest") != null
        || optionalText(attempt, "abort_proof_digest") != null
        || optionalText(attempt, "abort_reason") != null) {
      throw invalid("PENDING initial-admission attempt already contains terminal evidence");
    }
  }

  private void lockRequest(String namespace, String requestId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended('canonical-initial-admission-request:' || ? || ':' || ?, 0))",
        namespace,
        requestId);
  }

  private void lockTenantAndLaunch(
      String namespace, UUID canonicalTenantId, String controlRequestId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 1))",
        namespace,
        canonicalTenantId.toString());
    Record launch =
        dsl.fetchOne(
            "SELECT target_namespace FROM game_session_canonical_instance_launch"
                + " WHERE target_namespace = ? AND control_plane_request_id = ? FOR UPDATE",
            namespace,
            controlRequestId);
    if (launch == null) {
      throw invalid("Canonical launch row is unavailable for the initial-admission owner");
    }
  }

  private void lockAdmissionRealm(String namespace, UUID tenantId, UUID realmId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended('canonical-initial-admission-realm:' || ? || ':' || ? || ':' || ?, 2))",
        namespace,
        tenantId.toString(),
        realmId.toString());
  }

  private Record selectIntent(String namespace, String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT i.*, l.original_start_session_tuple AS launch_original_start_session_tuple,"
            + " c.realm_slug AS realm_slug, c.source_world_display_name AS source_world_display_name"
            + " FROM "
            + INTENT_TABLE
            + " i"
            + " LEFT JOIN game_session_canonical_instance_launch l ON l.target_namespace = i.target_namespace"
            + " AND l.control_plane_request_id = i.control_plane_request_id"
            + " LEFT JOIN game_session_canonical_realm_catalog c ON c.target_namespace = i.target_namespace"
            + " AND c.canonical_tenant_id = i.canonical_tenant_id AND c.realm_id = i.realm_id"
            + " WHERE i.target_namespace = ? AND i.initial_admission_request_id = ?"
            + (forUpdate ? " FOR UPDATE OF i" : ""),
        namespace,
        requestId);
  }

  private Record selectLaunchWithBinding(
      String namespace, String controlRequestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT l.*,"
            + " l.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'"
            + " AS launch_descriptor_request_digest,"
            + " l.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'"
            + " AS launch_descriptor_result_digest,"
            + " l.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest'"
            + " AS launch_release_attestation_digest FROM game_session_canonical_instance_launch l"
            + " WHERE l.target_namespace = ? AND l.control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE OF l" : ""),
        namespace,
        controlRequestId);
  }

  private Record selectInitialAttempt(String namespace, String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + ATTEMPT_TABLE
            + " WHERE target_namespace = ? AND initial_admission_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        namespace,
        requestId);
  }

  private String controlRequestForIntent(String initialRequestId) {
    Record row = selectIntent(workloadNamespace, initialRequestId, false);
    if (row == null) {
      throw invalid("Initial-admission intent is missing before launch lock acquisition");
    }
    return requiredText(row, "control_plane_request_id");
  }

  private static String terminalDigest(
      byte[] accountEvidence,
      String requestDigest,
      String outcome,
      Long pointerId,
      Long pointerVersion,
      Long auditEventId,
      Instant terminalAt,
      String abortReason) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream framed = new DataOutputStream(bytes)) {
      frame(framed, TERMINAL_DIGEST_DOMAIN.getBytes(StandardCharsets.UTF_8));
      frame(framed, accountEvidence);
      frame(framed, requestDigest.getBytes(StandardCharsets.UTF_8));
      frame(framed, outcome.getBytes(StandardCharsets.UTF_8));
      frame(
          framed, (pointerId == null ? "" : pointerId.toString()).getBytes(StandardCharsets.UTF_8));
      frame(
          framed,
          (pointerVersion == null ? "" : pointerVersion.toString())
              .getBytes(StandardCharsets.UTF_8));
      frame(
          framed,
          (auditEventId == null ? "" : auditEventId.toString()).getBytes(StandardCharsets.UTF_8));
      frame(framed, MICROS_UTC.format(terminalAt).getBytes(StandardCharsets.UTF_8));
      frame(framed, abortReason.getBytes(StandardCharsets.UTF_8));
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to frame terminal owner proof", impossible);
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void frame(DataOutputStream output, byte[] value) throws IOException {
    output.writeInt(value.length);
    output.write(value);
  }

  private OriginalStartSessionAdmissionTerminalResult requireRequestedTerminal(
      OriginalStartSessionAdmissionTerminalResult result, TerminalAction requested, String reason) {
    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome actual = result.ownerProof().outcome();
    if (requested == TerminalAction.COMMIT
        && actual != GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      throw invalid("Exact original initial-admission terminal is ABORTED, not COMMITTED");
    }
    if (requested == TerminalAction.ABORT
        && actual != GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED) {
      throw invalid("Exact original initial-admission terminal is COMMITTED, not ABORTED");
    }
    if (requested == TerminalAction.ABORT) {
      Optional<String> storedReason = readStoredAbortReason(result.request());
      if (storedReason.isEmpty() || !storedReason.get().equals(reason)) {
        throw invalid("Exact original ABORTED terminal has a different persisted abort reason");
      }
    }
    return result;
  }

  private Optional<String> readStoredAbortReason(
      OriginalStartSessionAdmissionTerminalRequest request) {
    return readTransaction.execute(
        status -> {
          requireReadOnlyRepeatableReadOwnerTransaction();
          WorldCanonicalInitialAdmissionHold.Request hold =
              request.protectionEvidence().request().worldAdmissionHoldIdentity().request();
          Record row =
              selectInitialAttempt(
                  request.targetNamespace(), hold.initialAdmissionRequestId(), false);
          if (row == null || !"ABORTED".equals(requiredText(row, "status"))) {
            return Optional.empty();
          }
          return Optional.of(requiredText(row, "abort_reason"));
        });
  }

  private void requireOriginalAttemptHistoricalMatch(
      Record terminalAttempt, AccountStartSessionAdmissionProtectionEvidence evidence) {
    AccountStartSessionAdmissionProtectionRequest protection = evidence.request();
    Record original =
        dsl.fetchOne(
            "SELECT * FROM game_session_start_session_operator_attempt WHERE target_namespace = ?"
                + " AND control_plane_request_id = ?",
            workloadNamespace,
            protection.originalTuple().controlPlaneRequestId());
    if (original == null
        || !"OWNER_EXECUTION_PENDING".equals(requiredText(original, "phase_state"))
        || !requiredUuid(original, "owner_attempt_id")
            .equals(protection.gameSessionOwnerAttemptId())
        || !requiredUuid(original, "owner_mutation_id")
            .equals(protection.gameSessionOwnerMutationId())
        || requiredLong(original, "owner_fence") != protection.gameSessionOwnerFence()
        || !requiredUuid(terminalAttempt, "original_owner_attempt_id")
            .equals(requiredUuid(original, "owner_attempt_id"))
        || !requiredUuid(terminalAttempt, "original_owner_mutation_id")
            .equals(requiredUuid(original, "owner_mutation_id"))
        || requiredLong(terminalAttempt, "original_owner_fence")
            != requiredLong(original, "owner_fence")
        || !requiredUuid(original, "canonical_tenant_id")
            .equals(requiredUuid(terminalAttempt, "canonical_tenant_id"))
        || !Arrays.equals(
            requiredBytes(original, "post_authorization_execution_tuple"),
            protection.originalPostAuthorizationTupleBytes())
        || !Arrays.equals(
            requiredBytes(original, "account_redemption_projection"),
            protection.accountRedemptionProjectionBytes())
        || !requiredInstant(original, "lease_expires_at")
            .equals(protection.originalLeaseExpiresAt())) {
      throw invalid(
          "Terminal readback does not match the retained exact V35 tuple, projection, owner, and lease");
    }
  }

  private Instant databaseClock() {
    Record row = dsl.fetchOne("SELECT clock_timestamp() AS terminal_at");
    return requiredInstant(row, "terminal_at");
  }

  private void requireOutsideOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical initial-admission public APIs own their independent transactions");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical initial admission requires writable READ COMMITTED");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation()
                  != java.sql.Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Canonical initial admission requires writable READ COMMITTED");
          }
          return null;
        });
    Record row =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation,"
                + " current_setting('transaction_read_only') AS read_only");
    if (row == null
        || !"read committed".equals(requiredText(row, "isolation"))
        || !"off".equals(requiredText(row, "read_only"))) {
      throw new IllegalStateException(
          "Canonical initial admission requires writable READ COMMITTED");
    }
  }

  private void requireReadOnlyRepeatableReadOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Terminal readback requires read-only REPEATABLE READ");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation()
                  != java.sql.Connection.TRANSACTION_REPEATABLE_READ) {
            throw new IllegalStateException("Terminal readback requires read-only REPEATABLE READ");
          }
          return null;
        });
    Record row =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation,"
                + " current_setting('transaction_read_only') AS read_only");
    if (row == null
        || !"repeatable read".equals(requiredText(row, "isolation"))
        || !"on".equals(requiredText(row, "read_only"))) {
      throw new IllegalStateException("Terminal readback requires read-only REPEATABLE READ");
    }
  }

  private static boolean isTerminal(String status) {
    return "COMMITTED".equals(status) || "ABORTED".equals(status);
  }

  private static String requiredText(Record row, String field) {
    String value = row == null ? null : row.get(field, String.class);
    if (value == null) {
      throw invalid("Persisted initial-admission owner is missing " + field);
    }
    return value;
  }

  private static String optionalText(Record row, String field) {
    return row == null ? null : row.get(field, String.class);
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row == null ? null : row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) {
      throw invalid("Persisted initial-admission owner is missing or has nil " + field);
    }
    return value;
  }

  private static UUID optionalUuid(Record row, String field) {
    return row == null ? null : row.get(field, UUID.class);
  }

  private static long requiredLong(Record row, String field) {
    Long value = optionalLong(row, field);
    if (value == null) {
      throw invalid("Persisted initial-admission owner is missing " + field);
    }
    return value.longValue();
  }

  static boolean sameRequiredLongValue(
      Record left, String leftField, Record right, String rightField) {
    return requiredLong(left, leftField) == requiredLong(right, rightField);
  }

  private static Long optionalLong(Record row, String field) {
    return row == null ? null : row.get(field, Long.class);
  }

  private static Integer requiredInteger(Record row, String field) {
    Integer value = nullableInteger(row, field);
    if (value == null) {
      throw invalid("Persisted initial-admission owner is missing " + field);
    }
    return value;
  }

  private static Integer nullableInteger(Record row, String field) {
    return row == null ? null : row.get(field, Integer.class);
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = optionalBytes(row, field);
    if (value == null) {
      throw invalid("Persisted initial-admission owner is missing " + field);
    }
    return value;
  }

  private static byte[] optionalBytes(Record row, String field) {
    byte[] value = row == null ? null : row.get(field, byte[].class);
    return value == null ? null : value.clone();
  }

  private static boolean requiredBoolean(Record row, String field) {
    Boolean value = row == null ? null : row.get(field, Boolean.class);
    if (value == null) {
      throw invalid("Persisted initial-admission owner is missing " + field);
    }
    return value;
  }

  private static Instant requiredInstant(Record row, String field) {
    java.time.OffsetDateTime value =
        row == null ? null : row.get(field, java.time.OffsetDateTime.class);
    if (value == null) {
      throw invalid("Persisted initial-admission owner is missing " + field);
    }
    return value.toInstant();
  }

  private static Instant optionalInstant(Record row, String field) {
    java.time.OffsetDateTime value =
        row == null ? null : row.get(field, java.time.OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(Record row, String field) {
    return row == null ? null : row.get(field, Long.class);
  }

  private static void requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be nonblank and within its bound");
    }
  }

  private static InvalidCanonicalInitialAdmissionException invalid(String message) {
    return new InvalidCanonicalInitialAdmissionException(message);
  }

  /** Exact caller-supplied pre-admission evidence; decoding remains integrity-only. */
  public record IntentInput(
      GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch launch,
      WorldCanonicalInstanceLifecycleEvidence lifecycle,
      String initialAdmissionRequestId,
      CanonicalInitialAdmissionRequest.OriginKind originKind,
      Long expectedPriorPointerVersion) {
    public IntentInput {
      Objects.requireNonNull(launch, "launch");
      Objects.requireNonNull(lifecycle, "lifecycle");
      Objects.requireNonNull(initialAdmissionRequestId, "initialAdmissionRequestId");
      Objects.requireNonNull(originKind, "originKind");
    }
  }

  private record PreparedIntent(
      GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch launch,
      WorldCanonicalInstanceLifecycleEvidence lifecycle,
      CanonicalRealmCatalog catalog,
      WorldCanonicalInitialAdmissionHold.Request holdRequest) {}

  private enum TerminalAction {
    COMMIT,
    ABORT
  }

  private enum TerminalWriteResult {
    WRITTEN,
    ALREADY_TERMINAL
  }

  public static final class InvalidCanonicalInitialAdmissionException
      extends IllegalStateException {
    public InvalidCanonicalInitialAdmissionException(String message) {
      super(message);
    }

    public InvalidCanonicalInitialAdmissionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
