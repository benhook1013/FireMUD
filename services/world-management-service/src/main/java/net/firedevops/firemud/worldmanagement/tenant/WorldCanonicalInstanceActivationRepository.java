package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local immutable activation ledger and exact World lifecycle CAS. */
public final class WorldCanonicalInstanceActivationRepository {
  private static final int MAX_PROTECTED_ACTIVATION_ATTEMPTS = 3;

  private final DSLContext dsl;
  private final TransactionTemplate readTransaction;
  private final TransactionTemplate writeTransaction;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository;

  public WorldCanonicalInstanceActivationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository, "lifecycleRepository");
    PlatformTransactionManager manager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    readTransaction = new TransactionTemplate(manager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);
    writeTransaction = new TransactionTemplate(manager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Independently reads an immutable result; exact retries intentionally do not require current
   * authority.
   */
  public Optional<WorldCanonicalInstanceActivation.Result> readResult(
      WorldCanonicalInstanceActivation.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction("World canonical activation result read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              // The old request-only transport path may replay retained V43 proofs, but it must
              // not disclose a new V65 protected terminal without the exact Account protection
              // identity that binds the receipt.
              if (dsl.fetchOne(
                      "SELECT 1 FROM world_canonical_instance_activation_fence "
                          + "WHERE activation_request_id=?",
                      request.activationRequestId())
                  != null) {
                return null;
              }
              return readResultInCurrentTransaction(request).orElse(null);
            }));
  }

  /**
   * Exact historical terminal lookup for Account settlement. Pending, absent, or legacy V43-only
   * operation rows are deliberately not returned as activation-terminal proof.
   */
  public Optional<WorldOriginalStartSessionActivationTerminalEvidence> readTerminalEvidence(
      AccountStartSessionActivationProtectionEvidence protection) {
    Objects.requireNonNull(protection, "protection");
    requireNoActiveTransaction("World original StartSession activation terminal read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              Record row =
                  activationFence(
                      protection.request().worldActivationRequest().activationRequestId());
              if (row == null) return null;
              requireSameProtectionIdentity(row, protection);
              String state = required(row, "operation_state", String.class);
              if ("PENDING".equals(state)) return null;
              byte[] terminalBytes = required(row, "terminal_evidence_bytes", byte[].class);
              if (!required(row, "terminal_evidence_digest", String.class)
                  .equals(digest(terminalBytes))) {
                throw new InvalidActivationEvidenceException(
                    "Stored original StartSession activation terminal digest is inconsistent");
              }
              try {
                WorldOriginalStartSessionActivationTerminalEvidence terminal =
                    WorldOriginalStartSessionActivationTerminalEvidence.decode(terminalBytes);
                if (terminal.worldActivationFence()
                        != required(row, "world_activation_fence", Long.class)
                    || !terminal
                        .activationRequestId()
                        .equals(protection.request().worldActivationRequest().activationRequestId())
                    || !terminal
                        .accountProtectionEvidence()
                        .requestDigest()
                        .equals(protection.requestDigest())
                    || !terminal.outcome().name().equals(state)) {
                  throw new InvalidActivationEvidenceException(
                      "Stored World activation terminal differs from its durable operation fence");
                }
                return terminal;
              } catch (IllegalArgumentException invalid) {
                throw new InvalidActivationEvidenceException(
                    "Stored World activation terminal evidence is invalid", invalid);
              }
            }));
  }

  /**
   * Read-only terminal-first recovery for an exact caller acquisition statement. This lookup does
   * not acquire Account authority, check lease currentness, or restart a PENDING operation.
   * Correlation IDs normalized by the activation request identity remain those of the first stored
   * protection receipt and terminal result.
   */
  public Optional<ProtectedActivationReadback> readProtectedActivationByAcquisitionInput(
      AccountStartSessionActivationProtectionAcquisitionInput input) {
    Objects.requireNonNull(input, "activation protection acquisition input");
    requireNoActiveTransaction("World protected activation recovery read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              var activation = input.worldActivationRequest();
              UUID activationId = activation.activationRequestId();
              UUID gameInstanceId = activation.canonicalGameInstanceId();
              Record row =
                  dsl.fetchOne(
                      "SELECT * FROM world_canonical_instance_activation_fence "
                          + "WHERE activation_request_id=? OR canonical_game_instance_id=?",
                      activationId,
                      gameInstanceId);
              if (row != null) {
                byte[] firstEvidenceBytes =
                    required(row, "account_protection_evidence_bytes", byte[].class);
                if (!required(row, "account_protection_evidence_digest", String.class)
                    .equals(digest(firstEvidenceBytes))) {
                  throw new InvalidActivationEvidenceException(
                      "Stored Account activation protection digest is inconsistent");
                }
                AccountStartSessionActivationProtectionEvidence firstProtection;
                try {
                  firstProtection =
                      AccountStartSessionActivationProtectionEvidence.decode(firstEvidenceBytes);
                } catch (IllegalArgumentException invalid) {
                  throw new InvalidActivationEvidenceException(
                      "Stored Account activation protection is invalid", invalid);
                }
                requireSameProtectionIdentity(row, firstProtection);
                requireSameAcquisitionInput(input, firstProtection);
                String state = required(row, "operation_state", String.class);
                if ("PENDING".equals(state)) return ProtectedActivationReadback.pendingReadback();
                byte[] terminalBytes = required(row, "terminal_evidence_bytes", byte[].class);
                if (!required(row, "terminal_evidence_digest", String.class)
                    .equals(digest(terminalBytes))) {
                  throw new InvalidActivationEvidenceException(
                      "Stored World activation terminal digest is inconsistent");
                }
                WorldOriginalStartSessionActivationTerminalEvidence terminal =
                    decodeTerminal(terminalBytes);
                byte[] resultBytes = required(row, "terminal_result_bytes", byte[].class);
                if (!required(row, "terminal_result_digest", String.class)
                    .equals(digest(resultBytes))) {
                  throw new InvalidActivationEvidenceException(
                      "Stored World activation result digest is inconsistent");
                }
                if (!Arrays.equals(
                        firstProtection.canonicalBytes(),
                        terminal.accountProtectionEvidence().canonicalBytes())
                    || !Arrays.equals(resultBytes, terminal.activationResult().canonicalBytes())
                    || !terminal.activationRequestId().equals(activationId)
                    || !terminal.outcome().name().equals(state)
                    || terminal.worldActivationFence()
                        != required(row, "world_activation_fence", Long.class)) {
                  throw new InvalidActivationEvidenceException(
                      "Stored World terminal differs from its original protected activation");
                }
                return ProtectedActivationReadback.terminal(terminal);
              }

              // V43-only results are retained history, not upgradeable protected receipts. Fail
              // locally before asking Account to create a new protection operation.
              if (dsl.fetchOne(
                      "SELECT 1 FROM world_canonical_instance_activation_operation "
                          + "WHERE activation_request_id=? "
                          + "OR (canonical_game_instance_id=? AND outcome='COMMITTED')",
                      activationId,
                      gameInstanceId)
                  != null) {
                throw new ActivationConflictException(
                    "A retained legacy World activation result cannot be upgraded to protected activation");
              }
              return null;
            }));
  }

  static void requireSameAcquisitionInput(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      AccountStartSessionActivationProtectionEvidence protection) {
    var request = protection.request();
    var activation = request.worldActivationRequest();
    var requestedActivation = input.worldActivationRequest();
    if (!Arrays.equals(
            input.originalPostAuthorizationTuple(), request.originalPostAuthorizationTupleBytes())
        || !input.gameSessionOwnerMutationId().equals(request.gameSessionOwnerMutationId())
        || !input.gameSessionOwnerAttemptId().equals(request.gameSessionOwnerAttemptId())
        || input.gameSessionOwnerFence() != request.gameSessionOwnerFence()
        || !requestedActivation.activationRequestId().equals(activation.activationRequestId())
        || !Arrays.equals(
            requestedActivation.canonicalRequestBytes(), activation.canonicalRequestBytes())) {
      throw new ActivationConflictException(
          "World activation retry changed its exact original caller acquisition statement");
    }
  }

  /** Result of a terminal-first lookup; a PENDING value must never trigger fresh acquisition. */
  public record ProtectedActivationReadback(
      boolean pending, WorldOriginalStartSessionActivationTerminalEvidence terminalEvidence) {
    public ProtectedActivationReadback {
      if (pending == (terminalEvidence != null)) {
        throw new IllegalArgumentException(
            "Protected activation readback must be exactly PENDING or a full terminal receipt");
      }
    }

    private static ProtectedActivationReadback pendingReadback() {
      return new ProtectedActivationReadback(true, null);
    }

    private static ProtectedActivationReadback terminal(
        WorldOriginalStartSessionActivationTerminalEvidence terminalEvidence) {
      return new ProtectedActivationReadback(false, Objects.requireNonNull(terminalEvidence));
    }
  }

  /**
   * Retains the exact first Account activation-protection receipt before attempting the lifecycle
   * CAS. Replays compare stable identity (which normalizes only owner-read correlations) and never
   * replace the first receipt.
   */
  private ActivationIntent beginProtectedActivation(
      AccountStartSessionActivationProtectionEvidence protection) {
    return writeTransaction.execute(
        status -> {
          requireWritableTransaction();
          lockActivationOperation(protection);
          UUID activationId = protection.request().worldActivationRequest().activationRequestId();
          Record prior = activationFenceForUpdate(activationId);
          if (prior != null) {
            requireSameProtectionIdentity(prior, protection);
            return intent(prior, false);
          }
          requireNoLegacyActivationResult(protection);
          requireLocalProtectedActivationBinding(protection, true);
          Record inserted = insertActivationFence(protection);
          return intent(
              Objects.requireNonNull(inserted, "World activation fence insert returned no row"),
              true);
        });
  }

  /**
   * Creates or completes one protected initial activation. The Account protection remains pending
   * until its caller consumes the exact returned terminal through Account's authenticated owner
   * settlement path; this repository does not release it. Only the invocation that created the
   * pending fence may retry its lifecycle transaction after a transient data access failure.
   */
  public WorldOriginalStartSessionActivationTerminalEvidence activateProtected(
      AccountStartSessionActivationProtectionEvidence protection,
      WorldCanonicalInstanceActivationService.HeldActivationAuthority held) {
    Objects.requireNonNull(protection, "protection");
    Objects.requireNonNull(held, "held");
    requireNoActiveTransaction("World protected canonical activation");
    ActivationIntent intent = beginProtectedActivation(protection);
    if (!"PENDING".equals(intent.operationState())) {
      return decodeTerminal(intent.terminalEvidenceBytes());
    }
    if (!intent.createdByThisInvocation()) {
      throw new WorldCanonicalInstanceActivationService.ActivationDeniedException(
          "An existing pending World activation intent cannot be restarted; only exact terminal readback or a positive fenced abort may continue");
    }
    AccountStartSessionActivationProtectionEvidence firstProtection =
        AccountStartSessionActivationProtectionEvidence.decode(
            intent.accountProtectionEvidenceBytes());
    WorldCanonicalInstanceActivation.Request request =
        firstProtection.request().worldActivationRequest();
    // Retain PENDING once; retries repeat only this invocation's lifecycle transaction.
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        writeTransaction.execute(
            status -> {
              requireWritableTransaction();
              held.requireHeld();
              lockActivationOperation(firstProtection);
              Record fence = activationFenceForUpdate(request.activationRequestId());
              if (fence == null) {
                throw new InvalidActivationEvidenceException(
                    "Protected World activation lost its durable operation fence");
              }
              requireSameProtectionIdentity(fence, firstProtection);
              String state = required(fence, "operation_state", String.class);
              if (!"PENDING".equals(state)) return null;

              WorldCanonicalInstanceLifecycleEvidence current =
                  lifecycleRepository
                      .readForActivationInOwnerTransaction(request.preparing().request())
                      .orElseThrow(
                          () ->
                              new ActivationConflictException(
                                  "Protected World activation no longer has its exact materialized owner association"));
              requireLocalProtectedActivationBinding(
                  firstProtection, false, required(fence, "world_execution_fence", Long.class));
              requireLeaseStillValidAfterLocalLockWait(fence);

              Optional<WorldCanonicalInstanceActivation.Result> oldResult =
                  readResultInCurrentTransaction(request);
              if (oldResult.isPresent()) {
                throw new ActivationConflictException(
                    "A legacy or independently terminal World activation result cannot be upgraded to Account-protected proof");
              }
              boolean exactPrecondition = exactPreparingPrecondition(request, current);
              if (!exactPrecondition) {
                WorldCanonicalInstanceActivation.Result aborted =
                    new WorldCanonicalInstanceActivation.Result(
                        request,
                        WorldCanonicalInstanceActivation.Outcome.ABORTED,
                        "PRECONDITION_FAILED",
                        current);
                insertResult(request, aborted);
                completeActivationFence(
                    fence,
                    firstProtection,
                    aborted,
                    WorldCanonicalInstanceActivation.Outcome.ABORTED);
                held.requireHeld();
                return null;
              }

              WorldCanonicalInstanceLifecycleEvidence predicted =
                  nextActiveEvidence(request, current);
              WorldCanonicalInstanceActivation.Result committed =
                  new WorldCanonicalInstanceActivation.Result(
                      request, WorldCanonicalInstanceActivation.Outcome.COMMITTED, null, predicted);
              insertResult(request, committed);
              held.requireHeld();
              // V43's exact execution manifest predicts this row with the transaction timestamp.
              int updated =
                  dsl.execute(
                      "UPDATE world_instance SET status='ACTIVE', lifecycle_epoch=lifecycle_epoch+1, "
                          + "row_version=row_version+1, updated_at=CURRENT_TIMESTAMP "
                          + "WHERE id=(SELECT world_instance_id FROM world_canonical_instance_association "
                          + "WHERE canonical_game_instance_id=? AND schema_version=2 "
                          + "AND canonical_realm_id=? AND catalog_revision=?) "
                          + "AND canonical_game_instance_id=? AND canonical_realm_id=? AND catalog_revision=? "
                          + "AND status='PREPARING' AND lifecycle_epoch=? AND row_version=?",
                      request.canonicalGameInstanceId(),
                      firstProtection.request().launchAssociation().canonicalRealmId(),
                      firstProtection.request().launchAssociation().catalogRevision(),
                      request.canonicalGameInstanceId(),
                      firstProtection.request().launchAssociation().canonicalRealmId(),
                      firstProtection.request().launchAssociation().catalogRevision(),
                      request.expectedLifecycleEpoch(),
                      request.expectedRowVersion());
              if (updated != 1) {
                throw new ActivationConflictException(
                    "Protected World lifecycle changed before its exact activation compare-and-set");
              }
              WorldCanonicalInstanceLifecycleEvidence actual =
                  lifecycleRepository
                      .readForActivationInOwnerTransaction(request.preparing().request())
                      .orElseThrow(
                          () ->
                              new InvalidActivationEvidenceException(
                                  "Protected World activation lost its owner readback"));
              if (!Arrays.equals(predicted.canonicalBytes(), actual.canonicalBytes())) {
                throw new InvalidActivationEvidenceException(
                    "Protected World activation result differs from its exact committed owner readback");
              }
              completeActivationFence(
                  fence,
                  firstProtection,
                  committed,
                  WorldCanonicalInstanceActivation.Outcome.COMMITTED);
              held.requireHeld();
              return null;
            });
        break;
      } catch (RuntimeException outcomeUncertain) {
        Optional<WorldOriginalStartSessionActivationTerminalEvidence> terminal =
            readTerminalEvidence(firstProtection);
        if (terminal.isPresent()) return terminal.orElseThrow();
        if (!(outcomeUncertain instanceof TransientDataAccessException)
            || attempts >= MAX_PROTECTED_ACTIVATION_ATTEMPTS) {
          throw outcomeUncertain;
        }
      }
    }
    return readTerminalEvidence(firstProtection)
        .orElseThrow(
            () ->
                new InvalidActivationEvidenceException(
                    "Protected World activation transaction committed without exact terminal readback"));
  }

  /**
   * Positively fences an activation as ABORTED under the same operation and lifecycle locks used by
   * activation. It is not called for timeout, expiry, missing evidence, or a failed owner read.
   */
  public WorldOriginalStartSessionActivationTerminalEvidence abortProtected(
      AccountStartSessionActivationProtectionEvidence protection, String terminalCode) {
    Objects.requireNonNull(protection, "protection");
    requireTerminalCode(terminalCode);
    requireNoActiveTransaction("World protected canonical activation abort");
    writeTransaction.execute(
        status -> {
          requireWritableTransaction();
          lockActivationOperation(protection);
          UUID activationId = protection.request().worldActivationRequest().activationRequestId();
          Record fence = activationFenceForUpdate(activationId);
          if (fence == null) {
            requireNoLegacyActivationResult(protection);
            requireLocalProtectedActivationBinding(protection, false);
            fence = insertActivationFence(protection);
          } else {
            requireSameProtectionIdentity(fence, protection);
          }
          if (!"PENDING".equals(required(fence, "operation_state", String.class))) return null;
          WorldCanonicalInstanceActivation.Request request =
              AccountStartSessionActivationProtectionEvidence.decode(
                      required(fence, "account_protection_evidence_bytes", byte[].class))
                  .request()
                  .worldActivationRequest();
          WorldCanonicalInstanceLifecycleEvidence current =
              lifecycleRepository
                  .readForActivationInOwnerTransaction(request.preparing().request())
                  .orElseThrow(
                      () ->
                          new ActivationConflictException(
                              "Cannot fence activation without exact current World lifecycle evidence"));
          requireLocalProtectedActivationBinding(
              AccountStartSessionActivationProtectionEvidence.decode(
                  required(fence, "account_protection_evidence_bytes", byte[].class)),
              false,
              required(fence, "world_execution_fence", Long.class));
          Optional<WorldCanonicalInstanceActivation.Result> oldResult =
              readResultInCurrentTransaction(request);
          if (oldResult.isPresent()) {
            throw new ActivationConflictException(
                "Existing unprotected World activation result cannot be converted into a fenced abort");
          }
          WorldCanonicalInstanceActivation.Result aborted =
              new WorldCanonicalInstanceActivation.Result(
                  request, WorldCanonicalInstanceActivation.Outcome.ABORTED, terminalCode, current);
          insertResult(request, aborted);
          completeActivationFence(
              fence,
              AccountStartSessionActivationProtectionEvidence.decode(
                  required(fence, "account_protection_evidence_bytes", byte[].class)),
              aborted,
              WorldCanonicalInstanceActivation.Outcome.ABORTED);
          return null;
        });
    return readTerminalEvidence(protection)
        .orElseThrow(
            () ->
                new InvalidActivationEvidenceException(
                    "Serialized World activation abort committed without exact terminal readback"));
  }

  /**
   * Commits one exact PREPARING-to-ACTIVE CAS or stores a terminal stale-precondition result. The
   * caller has already verified remote/current authorities outside this transaction.
   */
  public WorldCanonicalInstanceActivation.Result activate(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceActivationService.HeldActivationAuthority held) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(held, "held");
    requireNoActiveTransaction("World canonical activation");
    throw new WorldCanonicalInstanceActivationService.ActivationDeniedException(
        "Fresh canonical activation requires complete Account activation-purpose protection evidence");
  }

  private Record activationFence(UUID activationRequestId) {
    return dsl.fetchOne(
        "SELECT * FROM world_canonical_instance_activation_fence WHERE activation_request_id=?",
        activationRequestId);
  }

  private Record activationFenceForUpdate(UUID activationRequestId) {
    return dsl.fetchOne(
        "SELECT * FROM world_canonical_instance_activation_fence WHERE activation_request_id=? FOR UPDATE",
        activationRequestId);
  }

  private static ActivationIntent intent(Record row, boolean createdByThisInvocation) {
    return new ActivationIntent(
        required(row, "world_activation_fence", Long.class),
        required(row, "operation_state", String.class),
        required(row, "account_protection_evidence_bytes", byte[].class),
        row.get("terminal_evidence_bytes", byte[].class),
        createdByThisInvocation);
  }

  private Record insertActivationFence(AccountStartSessionActivationProtectionEvidence protection) {
    var request = protection.request();
    var worldRequest = request.worldActivationRequest();
    var scope = worldRequest.preparing().request();
    var launch = request.launchAssociation();
    byte[] accountBytes = protection.canonicalBytes();
    byte[] tupleBytes = request.originalPostAuthorizationTupleBytes();
    byte[] preparingBytes = worldRequest.preparingEvidenceBytes();
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO world_canonical_instance_activation_fence ("
                + "activation_request_id,protection_operation_digest,account_protection_id,account_protection_fence,"
                + "account_protection_evidence_bytes,account_protection_evidence_digest,source_capture_reference_digest,"
                + "target_namespace,canonical_tenant_id,control_plane_request_id,canonical_world_slug,canonical_game_instance_id,"
                + "original_post_authorization_tuple,account_world_participation_id,account_world_participation_fence,"
                + "game_session_owner_mutation_id,game_session_owner_attempt_id,game_session_owner_fence,"
                + "original_game_session_lease_expires_at,canonical_realm_id,catalog_revision,"
                + "expected_lifecycle_epoch,expected_row_version,preparing_evidence_bytes,world_execution_fence,"
                + "world_instance_id,world_activation_fence,operation_state) "
                + "SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS TIMESTAMPTZ),?,?,?,?,?,"
                + "e.world_execution_fence,e.world_instance_id,"
                + "nextval('world_canonical_instance_activation_fence_seq'),'PENDING' "
                + "FROM world_canonical_instance_execution e "
                + "JOIN world_canonical_instance_association a ON a.canonical_game_instance_id=e.canonical_game_instance_id "
                + "JOIN world_instance w ON w.id=a.world_instance_id "
                + "JOIN world_canonical_instance_preparation p ON p.canonical_game_instance_id=a.canonical_game_instance_id "
                + "WHERE e.target_namespace=? AND e.canonical_tenant_id=? AND e.control_plane_request_id=? "
                + "AND e.canonical_world_slug=? "
                + "AND e.canonical_game_instance_id=? AND e.original_post_authorization_tuple=? "
                + "AND e.account_world_participation_id=? AND e.account_world_participation_fence=? "
                + "AND e.game_session_owner_mutation_id=? AND e.game_session_owner_attempt_id=? "
                + "AND e.game_session_owner_fence=? AND e.original_game_session_lease_expires_at=CAST(? AS TIMESTAMPTZ) "
                + "AND e.operation_state='COMMITTED' AND e.world_instance_id=w.id "
                + "AND a.schema_version=2 AND a.canonical_realm_id=? AND a.catalog_revision=? "
                + "AND w.canonical_realm_id=a.canonical_realm_id AND w.catalog_revision=a.catalog_revision "
                + "AND p.input_digest=e.preparation_input_digest AND p.input_digest=? "
                + "AND p.input_json::jsonb->'identity'->>'canonicalRealmId'=a.canonical_realm_id::text "
                + "AND p.input_json::jsonb->'identity'->>'catalogRevision'=a.catalog_revision::text "
                + "ON CONFLICT DO NOTHING "
                + "RETURNING *",
            worldRequest.activationRequestId(),
            protection.requestDigest(),
            protection.accountProtectionId(),
            protection.accountProtectionFence(),
            accountBytes,
            digest(accountBytes),
            protection.originalSourceCaptureReferenceSha256(),
            scope.targetNamespace(),
            scope.canonicalTenantId(),
            scope.controlPlaneRequestId(),
            scope.worldSlug(),
            worldRequest.canonicalGameInstanceId(),
            tupleBytes,
            request.accountWorldParticipationId(),
            request.accountWorldParticipationFence(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
            launch.canonicalRealmId(),
            launch.catalogRevision(),
            worldRequest.expectedLifecycleEpoch(),
            worldRequest.preparing().rowVersion(),
            preparingBytes,
            scope.targetNamespace(),
            scope.canonicalTenantId(),
            scope.controlPlaneRequestId(),
            scope.worldSlug(),
            worldRequest.canonicalGameInstanceId(),
            tupleBytes,
            request.accountWorldParticipationId(),
            request.accountWorldParticipationFence(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
            launch.canonicalRealmId(),
            launch.catalogRevision(),
            worldRequest.preparing().preparationInputDigest());
    if (inserted == null) {
      throw new ActivationConflictException(
          "Another activation operation already owns this canonical instance, or the proof does not match the committed original execution, schema-2 owner association, realm/catalog, or preparation");
    }
    return inserted;
  }

  private void lockActivationOperation(AccountStartSessionActivationProtectionEvidence protection) {
    var request = protection.request();
    var world = request.worldActivationRequest();
    var scope = world.preparing().request();
    dsl.fetchOne(
        "SELECT world_lock_canonical_instance_execution(?,?,?,?)",
        scope.targetNamespace(),
        scope.canonicalTenantId(),
        scope.controlPlaneRequestId(),
        scope.canonicalGameInstanceId());
  }

  private void requireSameProtectionIdentity(
      Record row, AccountStartSessionActivationProtectionEvidence protection) {
    byte[] firstBytes = required(row, "account_protection_evidence_bytes", byte[].class);
    if (!required(row, "protection_operation_digest", String.class)
            .equals(protection.requestDigest())
        || !required(row, "account_protection_id", UUID.class)
            .equals(protection.accountProtectionId())
        || required(row, "account_protection_fence", Long.class)
            != protection.accountProtectionFence()
        || !required(row, "account_protection_evidence_digest", String.class)
            .equals(digest(firstBytes))) {
      throw new ActivationConflictException(
          "World activation identity is already bound to different Account protection evidence");
    }
    try {
      var first = AccountStartSessionActivationProtectionEvidence.decode(firstBytes);
      if (!Arrays.equals(first.operationIdentityBytes(), protection.operationIdentityBytes())
          || !first.acquiredAt().equals(protection.acquiredAt())
          || !first
              .originalSourceCaptureReferenceSha256()
              .equals(protection.originalSourceCaptureReferenceSha256())
          || !Arrays.equals(
              first.originalSourceCaptureReferenceBytes(),
              protection.originalSourceCaptureReferenceBytes())
          || !sameSourceEvidenceVector(first, protection)
          || !first.requestDigest().equals(protection.requestDigest())
          || !Arrays.equals(
              first.request().worldActivationRequest().canonicalRequestBytes(),
              protection.request().worldActivationRequest().canonicalRequestBytes())) {
        throw new ActivationConflictException(
            "World activation retry changed its original protection or activation identity");
      }
    } catch (IllegalArgumentException invalid) {
      throw new InvalidActivationEvidenceException(
          "Stored original Account activation protection is invalid", invalid);
    }
  }

  private static boolean sameSourceEvidenceVector(
      AccountStartSessionActivationProtectionEvidence first,
      AccountStartSessionActivationProtectionEvidence retry) {
    var firstSources = first.sourceEvidenceVector();
    var retrySources = retry.sourceEvidenceVector();
    if (firstSources.size() != retrySources.size()) return false;
    for (int index = 0; index < firstSources.size(); index++) {
      if (!Arrays.equals(
          firstSources.get(index).canonicalBytes(), retrySources.get(index).canonicalBytes())) {
        return false;
      }
    }
    return true;
  }

  private void requireNoLegacyActivationResult(
      AccountStartSessionActivationProtectionEvidence protection) {
    UUID activationId = protection.request().worldActivationRequest().activationRequestId();
    UUID gameInstanceId = protection.request().worldActivationRequest().canonicalGameInstanceId();
    if (dsl.fetchOne(
            "SELECT 1 FROM world_canonical_instance_activation_operation "
                + "WHERE activation_request_id=? OR (canonical_game_instance_id=? AND outcome='COMMITTED')",
            activationId,
            gameInstanceId)
        != null) {
      throw new ActivationConflictException(
          "A retained legacy or independently committed World activation cannot be upgraded to Account-protected activation");
    }
  }

  private void requireLocalProtectedActivationBinding(
      AccountStartSessionActivationProtectionEvidence protection, boolean requireLiveLease) {
    requireLocalProtectedActivationBinding(protection, requireLiveLease, null);
  }

  private void requireLocalProtectedActivationBinding(
      AccountStartSessionActivationProtectionEvidence protection,
      boolean requireLiveLease,
      Long expectedWorldExecutionFence) {
    var request = protection.request();
    var world = request.worldActivationRequest();
    var scope = world.preparing().request();
    var launch = request.launchAssociation();
    Record row =
        dsl.fetchOne(
            "SELECT e.world_execution_fence,e.original_game_session_lease_expires_at "
                + "FROM world_canonical_instance_execution e "
                + "JOIN world_canonical_instance_association a ON a.canonical_game_instance_id=e.canonical_game_instance_id "
                + "JOIN world_instance w ON w.id=a.world_instance_id "
                + "JOIN world_canonical_instance_preparation p ON p.canonical_game_instance_id=a.canonical_game_instance_id "
                + "WHERE e.target_namespace=? AND e.canonical_tenant_id=? AND e.control_plane_request_id=? "
                + "AND e.canonical_world_slug=? "
                + "AND e.canonical_game_instance_id=? AND e.original_post_authorization_tuple=? "
                + "AND e.account_world_participation_id=? AND e.account_world_participation_fence=? "
                + "AND e.game_session_owner_mutation_id=? AND e.game_session_owner_attempt_id=? "
                + "AND e.game_session_owner_fence=? AND e.original_game_session_lease_expires_at=CAST(? AS TIMESTAMPTZ) "
                + "AND e.operation_state='COMMITTED' AND e.world_instance_id=w.id "
                + "AND a.schema_version=2 AND a.canonical_realm_id=? AND a.catalog_revision=? "
                + "AND w.canonical_realm_id=a.canonical_realm_id AND w.catalog_revision=a.catalog_revision "
                + "AND p.input_digest=e.preparation_input_digest AND p.input_digest=? "
                + "AND p.input_json::jsonb->'identity'->>'canonicalRealmId'=a.canonical_realm_id::text "
                + "AND p.input_json::jsonb->'identity'->>'catalogRevision'=a.catalog_revision::text",
            scope.targetNamespace(),
            scope.canonicalTenantId(),
            scope.controlPlaneRequestId(),
            scope.worldSlug(),
            scope.canonicalGameInstanceId(),
            request.originalPostAuthorizationTupleBytes(),
            request.accountWorldParticipationId(),
            request.accountWorldParticipationFence(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
            launch.canonicalRealmId(),
            launch.catalogRevision(),
            world.preparing().preparationInputDigest());
    if (row == null) {
      throw new ActivationConflictException(
          "Account activation protection differs from the exact committed World execution, schema-2 local realm/catalog association, or preparation");
    }
    if (expectedWorldExecutionFence != null
        && required(row, "world_execution_fence", Long.class).longValue()
            != expectedWorldExecutionFence.longValue()) {
      throw new ActivationConflictException(
          "World activation intent differs from the durable original execution fence");
    }
    if (requireLiveLease) requireLeaseStillValid(row);
  }

  private void requireLeaseStillValidAfterLocalLockWait(Record fence) {
    Record row =
        dsl.fetchOne(
            "SELECT clock_timestamp() < ?::timestamptz AS valid",
            required(fence, "original_game_session_lease_expires_at", OffsetDateTime.class));
    if (row == null || !Boolean.TRUE.equals(required(row, "valid", Boolean.class))) {
      throw new WorldCanonicalInstanceActivationService.ActivationDeniedException(
          "Original Game Session lease expired before World activation admission; only exact readback or fenced abort may continue");
    }
  }

  private void requireLeaseStillValid(Record ownerRow) {
    Record row =
        dsl.fetchOne(
            "SELECT clock_timestamp() < ?::timestamptz AS valid",
            required(ownerRow, "original_game_session_lease_expires_at", OffsetDateTime.class));
    if (row == null || !Boolean.TRUE.equals(required(row, "valid", Boolean.class))) {
      throw new WorldCanonicalInstanceActivationService.ActivationDeniedException(
          "Original Game Session lease expired before World activation intent retention");
    }
  }

  private void completeActivationFence(
      Record fence,
      AccountStartSessionActivationProtectionEvidence firstProtection,
      WorldCanonicalInstanceActivation.Result result,
      WorldCanonicalInstanceActivation.Outcome outcome) {
    WorldOriginalStartSessionActivationTerminalEvidence terminal =
        WorldOriginalStartSessionActivationTerminalEvidence.create(
            firstProtection, result, required(fence, "world_activation_fence", Long.class));
    byte[] resultBytes = result.canonicalBytes();
    byte[] terminalBytes = terminal.canonicalBytes();
    int changed =
        dsl.execute(
            "UPDATE world_canonical_instance_activation_fence SET operation_state=?, "
                + "terminal_result_bytes=?, terminal_result_digest=?, terminal_evidence_bytes=?, "
                + "terminal_evidence_digest=?, terminal_at=clock_timestamp() "
                + "WHERE activation_request_id=? AND world_activation_fence=? AND operation_state='PENDING'",
            outcome.name(),
            resultBytes,
            digest(resultBytes),
            terminalBytes,
            digest(terminalBytes),
            result.request().activationRequestId(),
            required(fence, "world_activation_fence", Long.class));
    if (changed != 1) {
      throw new ActivationConflictException(
          "World activation terminal fence changed before its immutable terminal write");
    }
  }

  private static WorldOriginalStartSessionActivationTerminalEvidence decodeTerminal(byte[] bytes) {
    if (bytes == null) {
      throw new InvalidActivationEvidenceException(
          "World activation fence is terminal without immutable terminal evidence");
    }
    try {
      return WorldOriginalStartSessionActivationTerminalEvidence.decode(bytes);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidActivationEvidenceException(
          "Stored World activation terminal evidence is invalid", invalid);
    }
  }

  private static boolean exactPreparingPrecondition(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current) {
    return "PREPARING".equals(current.lifecycleStatus())
        && current.lifecycleEpoch() == request.expectedLifecycleEpoch()
        && current.rowVersion() == request.expectedRowVersion()
        && Arrays.equals(
            request.canonicalRequestBytes(),
            new WorldCanonicalInstanceActivation.Request(request.activationRequestId(), current)
                .canonicalRequestBytes());
  }

  private static void requireTerminalCode(String terminalCode) {
    if (terminalCode == null
        || terminalCode.isBlank()
        || terminalCode.length() > 64
        || !terminalCode.equals(terminalCode.trim())) {
      throw new IllegalArgumentException(
          "World activation terminal code must be 1 to 64 trimmed characters");
    }
  }

  private record ActivationIntent(
      long worldActivationFence,
      String operationState,
      byte[] accountProtectionEvidenceBytes,
      byte[] terminalEvidenceBytes,
      boolean createdByThisInvocation) {}

  private Optional<WorldCanonicalInstanceActivation.Result> readResultInCurrentTransaction(
      WorldCanonicalInstanceActivation.Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT request_digest, request_bytes, result_bytes, result_digest "
                + "FROM world_canonical_instance_activation_operation WHERE activation_request_id=?",
            request.activationRequestId());
    if (row == null) return Optional.empty();
    byte[] requestBytes = required(row, "request_bytes", byte[].class);
    String digest = required(row, "request_digest", String.class);
    byte[] resultBytes = required(row, "result_bytes", byte[].class);
    if (!digest.equals(request.requestDigest())
        || !Arrays.equals(requestBytes, request.canonicalRequestBytes())) {
      throw new ActivationConflictException(
          "Canonical World activation request ID was reused with changed immutable bindings");
    }
    if (!required(row, "result_digest", String.class).equals(digest(resultBytes))) {
      throw new InvalidActivationEvidenceException(
          "Stored canonical World activation result digest is inconsistent");
    }
    try {
      WorldCanonicalInstanceActivation.Result result =
          WorldCanonicalInstanceActivation.Result.fromStored(resultBytes);
      if (!result.request().activationRequestId().equals(request.activationRequestId())
          || !Arrays.equals(result.request().canonicalRequestBytes(), requestBytes)) {
        throw new InvalidActivationEvidenceException(
            "Stored canonical World activation result differs from its exact operation identity");
      }
      return Optional.of(result);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidActivationEvidenceException(
          "Stored canonical World activation result is invalid", invalid);
    }
  }

  private void insertResult(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceActivation.Result result) {
    byte[] resultBytes = result.canonicalBytes();
    dsl.execute(
        "INSERT INTO world_canonical_instance_activation_operation "
            + "(activation_request_id,request_digest,request_bytes,preparing_evidence_bytes,"
            + "canonical_game_instance_id,world_instance_id,expected_lifecycle_epoch,expected_row_version,"
            + "outcome,terminal_code,result_lifecycle_epoch,result_row_version,result_bytes,result_digest) "
            + "VALUES (?,?,?,?,?,(SELECT world_instance_id FROM world_canonical_instance_association "
            + "WHERE canonical_game_instance_id=?),?,?,?,?,?,?,?,?)",
        request.activationRequestId(),
        request.requestDigest(),
        request.canonicalRequestBytes(),
        request.preparingEvidenceBytes(),
        request.canonicalGameInstanceId(),
        request.canonicalGameInstanceId(),
        request.expectedLifecycleEpoch(),
        request.expectedRowVersion(),
        result.outcome().name(),
        result.terminalCode(),
        result.lifecycleEvidence().lifecycleEpoch(),
        result.lifecycleEvidence().rowVersion(),
        resultBytes,
        digest(resultBytes));
  }

  private static WorldCanonicalInstanceLifecycleEvidence nextActiveEvidence(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request.preparing().request(),
        current.launchBinding(),
        current.startLocation(),
        current.runtimeRoomInstanceId(),
        "ACTIVE",
        Math.addExact(request.expectedLifecycleEpoch(), 1L),
        Math.addExact(request.expectedRowVersion(), 1L),
        current.captureId(),
        current.graphSha256(),
        current.preparationInputDigest(),
        current.operationalRegionAssignments());
  }

  private void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World activation result read requires a read-only REPEATABLE READ transaction");
    }
    Record state = transactionState();
    if (!"repeatable read".equals(required(state, "isolation", String.class))
        || !"on".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "World activation result read requires a read-only REPEATABLE READ transaction");
    }
  }

  private void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World activation requires a writable READ COMMITTED transaction");
    }
    Record state = transactionState();
    String actualIsolation = required(state, "isolation", String.class);
    if (!"read committed".equals(actualIsolation)
        || !"off".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "World activation requires a writable READ COMMITTED transaction");
    }
  }

  private Record transactionState() {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only"),
        "World transaction state query returned no row");
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(label + " must not join an ambient transaction");
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    return Objects.requireNonNull(row.get(field, type), "Persisted " + field + " is null");
  }

  public static final class ActivationConflictException extends IllegalStateException {
    public ActivationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidActivationEvidenceException extends IllegalStateException {
    public InvalidActivationEvidenceException(String message) {
      super(message);
    }

    public InvalidActivationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
