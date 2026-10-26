package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered SQL owner for a single original-attempt INITIAL account barrier target.
 *
 * <p>This owner serializes with account-wide acquisition through inventory-clock then existing
 * account-control locks. It never inserts missing account control and never changes Account token,
 * admission-lease, or finalization authority.
 */
final class CanonicalGameplayAccountInitialBarrierInitializerRepository {
  private static final String CONTROL = "game_session_canonical_account_coverage_control";
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";
  private static final String ORIGINAL = "game_session_initial_gameplay_login_journal";
  private static final String INITIALIZER =
      "game_session_canonical_initial_account_barrier_initializer";
  private static final String TARGET = "game_session_canonical_initial_account_barrier_target";
  private static final String NO_ACTIVE = "NO_ACTIVE_ACCOUNT_WIDE_FLOW";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;
  private final Clock clock;

  CanonicalGameplayAccountInitialBarrierInitializerRepository(DSLContext dsl) {
    this(dsl, Clock.systemUTC());
  }

  CanonicalGameplayAccountInitialBarrierInitializerRepository(DSLContext dsl, Clock clock) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Captures one exact initial target under the same inventory-clock then account-control order
   * used by account-wide acquisition. The Account account id is taken from the authenticated result.
   */
  InitializerSnapshot beginOrRead(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult accountLogin,
      UUID tenantId,
      UUID playableStateNamespaceId) {
    Objects.requireNonNull(original, "original attempt");
    Objects.requireNonNull(accountLogin, "authenticated Account LOGIN result");
    requireNonNil(tenantId, "tenantId");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    requireNoAmbientTransaction();
    CanonicalGameplayLoginResult.NonSecretEvidence accountEvidence = accountLogin.evidence();
    requireOriginalEvidence(original, accountEvidence);
    String accountEvidenceDigest = digestAccountEvidence(accountEvidence);

    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger inventoryRevision = lockInventoryRevision(transaction);
          Record control = lockExistingControl(transaction, accountEvidence.accountId());
          AccountControlSource source = readNoActiveControl(control, accountEvidence.accountId());
          if (inventoryRevision.signum() <= 0) {
            throw conflict("Initial account barrier requires positive canonical inventory revision");
          }
          requireExactOriginalLogin(transaction, original, accountEvidence);

          Record prior = readInitializer(transaction, original.requestId(), true);
          if (prior != null) {
            InitializerSnapshot snapshot = toSnapshot(prior);
            requireSameOriginalTarget(
                snapshot,
                original,
                accountEvidence.accountId(),
                tenantId,
                playableStateNamespaceId,
                accountEvidenceDigest);
            requireSameCurrentSource(snapshot, source);
            return snapshot;
          }

          UUID initializerId = randomNonNilUuid();
          while (initializerId.equals(original.requestId())
              || initializerId.equals(original.callerContextId())
              || initializerId.equals(accountEvidence.accountId())) {
            initializerId = randomNonNilUuid();
          }
          transaction.execute(
              "INSERT INTO "
                  + INITIALIZER
                  + " (initializer_id, original_request_id, original_caller_context_id,"
                  + " original_attempt_fence, account_id, account_login_evidence_digest,"
                  + " source_control_state, source_control_epoch, inventory_snapshot_revision,"
                  + " source_control_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              initializerId,
              original.requestId(),
              original.callerContextId(),
              original.expectedAttemptFence(),
              accountEvidence.accountId(),
              accountEvidenceDigest,
              NO_ACTIVE,
              new BigDecimal(source.epoch()),
              new BigDecimal(inventoryRevision),
              source.digest());
          transaction.execute(
              "INSERT INTO "
                  + TARGET
                  + " (initializer_id, original_request_id, account_id, target_ordinal, tenant_id,"
                  + " playable_state_namespace_id, target_state)"
                  + " VALUES (?, ?, ?, 0, ?, ?, 'NEVER_DISPATCHED')",
              initializerId,
              original.requestId(),
              accountEvidence.accountId(),
              tenantId,
              playableStateNamespaceId);
          Record created = readInitializer(transaction, original.requestId(), true);
          if (created == null) throw conflict("Initial account barrier target readback is missing");
          InitializerSnapshot snapshot = toSnapshot(created);
          requireSameOriginalTarget(
              snapshot,
              original,
              accountEvidence.accountId(),
              tenantId,
              playableStateNamespaceId,
              accountEvidenceDigest);
          requireSameCurrentSource(snapshot, source);
          return snapshot;
        });
  }

  /** Reads the exact live owner context for protected INITIAL incarnation observation. */
  InitialObservationContext readCurrentObservationContext(UUID originalRequestId) {
    requireNonNil(originalRequestId, "originalRequestId");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          Record row = readInitializer(transaction, originalRequestId, false);
          if (row == null) throw conflict("Exact original INITIAL account target is missing");
          InitializerSnapshot snapshot = toSnapshot(row);
          AccountControlSource current = readCurrentNoActiveControl(transaction, snapshot.accountId());
          requireSameCurrentSource(snapshot, current);
          requireOriginalJournalStillCurrent(transaction, snapshot);
          return InitialObservationContext.from(snapshot);
        });
  }

  InitializerSnapshot readCurrent(UUID originalRequestId) {
    requireNonNil(originalRequestId, "originalRequestId");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          InitializerSnapshot snapshot = readRequiredCurrent(transaction, originalRequestId, false);
          AccountControlSource current = readCurrentNoActiveControl(transaction, snapshot.accountId());
          requireSameCurrentSource(snapshot, current);
          requireOriginalJournalStillCurrent(transaction, snapshot);
          return snapshot;
        });
  }

  /**
   * Commits the one-time remote-attempt state and its qualified pre-dispatch source before Redis is
   * called. No subsequent path can receive another create permission.
   */
  InitializerSnapshot recordDispatchPossible(
      UUID originalRequestId,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation beforeIncarnation) {
    requireNonNil(originalRequestId, "originalRequestId");
    Objects.requireNonNull(beforeIncarnation, "beforeIncarnation");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          lockInventoryRevision(transaction);
          Record locator = readInitializer(transaction, originalRequestId, false);
          if (locator == null) throw conflict("Exact original INITIAL account target is missing");
          InitializerSnapshot located = toSnapshot(locator);
          Record control = lockExistingControl(transaction, located.accountId());
          AccountControlSource source = readNoActiveControl(control, located.accountId());
          InitializerSnapshot current = readRequiredCurrent(transaction, originalRequestId, true);
          requireSameCurrentSource(current, source);
          requireOriginalJournalStillCurrent(transaction, current);
          requireInitialObservationMatches(current, beforeIncarnation);
          if (current.state() != State.NEVER_DISPATCHED) {
            throw conflict("INITIAL remote attempt was already possible and cannot be reissued");
          }
          int updated =
              transaction.execute(
                  "UPDATE "
                      + TARGET
                      + " SET target_state = 'DISPATCH_POSSIBLE',"
                      + " before_accepted_attestation_digest = ?, before_incarnation_digest = ?,"
                      + " before_source_payload_digest = ?,"
                      + " before_observation_issued_at_epoch_millis = ?,"
                      + " before_observation_observed_at_epoch_millis = ?,"
                      + " before_observation_expires_at_epoch_millis = ?"
                      + " WHERE initializer_id = ? AND target_ordinal = 0"
                      + " AND target_state = 'NEVER_DISPATCHED'",
                  beforeIncarnation.acceptedAttestationDigest(),
                  beforeIncarnation.incarnationDigest(),
                  beforeIncarnation.payloadDigest(),
                  beforeIncarnation.issuedAtEpochMillis(),
                  beforeIncarnation.observedAtEpochMillis(),
                  beforeIncarnation.expiresAtEpochMillis(),
                  current.initializerId());
          requireOne(updated, "INITIAL one-time remote dispatch claim was lost");
          InitializerSnapshot saved = readRequiredCurrent(transaction, originalRequestId, true);
          if (saved.state() != State.DISPATCH_POSSIBLE
              || !saved.beforeIncarnationDigest().equals(beforeIncarnation.incarnationDigest())
              || !saved.beforeSourcePayloadDigest().equals(beforeIncarnation.payloadDigest())) {
            throw conflict("INITIAL remote dispatch state did not read back exactly");
          }
          requireSameCurrentSource(saved, source);
          return saved;
        });
  }

  /**
   * Accepts exact physical OPEN readback only while the positively read source control still has
   * its captured NO_ACTIVE control epoch. This receipt is current SQL evidence, not a later
   * admission decision.
   */
  InitializerSnapshot acceptOpenReadback(
      UUID originalRequestId,
      CanonicalGameplayAccountInitialBarrierRedisOwner.Readback readback) {
    requireNonNil(originalRequestId, "originalRequestId");
    Objects.requireNonNull(readback, "readback");
    requireNoAmbientTransaction();
    if (!originalRequestId.equals(readback.originalRequestId())) {
      throw conflict("INITIAL readback belongs to a different original request");
    }
    requireReadback(readback);
    long now = clock.millis();
    if (now <= 0
        || now < readback.beforeIncarnation().issuedAtEpochMillis()
        || now >= readback.beforeIncarnation().expiresAtEpochMillis()
        || now < readback.afterIncarnation().issuedAtEpochMillis()
        || now >= readback.afterIncarnation().expiresAtEpochMillis()) {
      throw conflict("INITIAL protected incarnation observation expired before SQL completion");
    }
    String fieldsDigest = digestFields(readback.value().fields());

    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          lockInventoryRevision(transaction);
          Record locator = readInitializer(transaction, originalRequestId, false);
          if (locator == null) throw conflict("Exact original INITIAL account target is missing");
          InitializerSnapshot located = toSnapshot(locator);
          Record control = lockExistingControl(transaction, located.accountId());
          AccountControlSource source = readNoActiveControl(control, located.accountId());
          InitializerSnapshot current = readRequiredCurrent(transaction, originalRequestId, true);
          requireSameCurrentSource(current, source);
          requireOriginalJournalStillCurrent(transaction, current);
          requireSameReadbackContext(current, readback);
          if (current.state() == State.OPEN_INSTALLED) {
            if (!fieldsDigest.equals(current.installedFieldsDigest())) {
              throw conflict("An installed INITIAL OPEN value cannot be replaced");
            }
            return current;
          }
          if (current.state() != State.DISPATCH_POSSIBLE) {
            throw conflict("INITIAL OPEN readback has no persisted possible-dispatch state");
          }
          int updated =
              transaction.execute(
                  "UPDATE "
                      + TARGET
                      + " SET target_state = 'OPEN_INSTALLED', installed_fields_digest = ?,"
                      + " completed_at = CURRENT_TIMESTAMP"
                      + " WHERE initializer_id = ? AND target_ordinal = 0"
                      + " AND target_state = 'DISPATCH_POSSIBLE'"
                      + " AND before_incarnation_digest = ?"
                      + " AND before_accepted_attestation_digest = ?",
                  fieldsDigest,
                  current.initializerId(),
                  readback.beforeIncarnation().incarnationDigest(),
                  readback.beforeIncarnation().acceptedAttestationDigest());
          requireOne(updated, "INITIAL OPEN completion lost its exact original SQL target");
          InitializerSnapshot saved = readRequiredCurrent(transaction, originalRequestId, true);
          if (saved.state() != State.OPEN_INSTALLED
              || !fieldsDigest.equals(saved.installedFieldsDigest())) {
            throw conflict("INITIAL OPEN completion readback differs from its exact SQL postimage");
          }
          requireSameCurrentSource(saved, source);
          return saved;
        });
  }

  void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("INITIAL account barrier phases require no ambient SQL transaction");
    }
    try {
      dsl.connectionResult(
          connection -> {
            if (!connection.getAutoCommit()) {
              throw conflict("INITIAL account barrier phases cannot join a jOOQ transaction");
            }
            return Boolean.TRUE;
          });
    } catch (RuntimeException failure) {
      if (failure instanceof CanonicalGameplayBindingInventoryConflictException) throw failure;
      throw conflict("INITIAL account barrier SQL connection state is unavailable");
    }
  }

  private static void requireReadback(CanonicalGameplayAccountInitialBarrierRedisOwner.Readback readback) {
    CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before =
        readback.beforeIncarnation();
    CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after =
        readback.afterIncarnation();
    if (!readback.value().fields().equals(readback.barrierFields())
        || !CanonicalGameplayAccountInitialBarrierValue.FIELDS.equals(
            readback.barrierFields().keySet())
        || !"OPEN".equals(readback.barrierFields().get("state"))
        || !readback.beforeIncarnation()
            .sourceControlEpoch()
            .equals(readback.barrierFields().get("ownerFenceEpoch"))
        || !readback.afterIncarnation()
            .sourceControlEpoch()
            .equals(readback.barrierFields().get("ownerFenceEpoch"))
        || !before.incarnationDigest().equals(after.incarnationDigest())
        || !before.acceptedAttestationDigest().equals(after.acceptedAttestationDigest())
        || after.issuedAtEpochMillis() < before.issuedAtEpochMillis()
        || after.observedAtEpochMillis() < before.observedAtEpochMillis()) {
      throw conflict("Physical INITIAL OPEN readback or protected source tuple is not exact");
    }
  }

  private static void requireSameReadbackContext(
      InitializerSnapshot current,
      CanonicalGameplayAccountInitialBarrierRedisOwner.Readback readback) {
    requireInitialObservationMatches(current, readback.beforeIncarnation());
    requireInitialObservationMatches(current, readback.afterIncarnation());
    if (!current.initializerId().equals(readback.initializerId())
        || !current.originalRequestId().equals(readback.originalRequestId())
        || !current.accountId().equals(readback.accountId())
        || !current.tenantId().equals(readback.tenantId())
        || !current.playableStateNamespaceId().equals(readback.playableStateNamespaceId())
        || !current.accountLoginEvidenceDigest().equals(readback.accountLoginEvidenceDigest())
        || !current.sourceControlDigest().equals(readback.sourceControlDigest())
        || !current.inventorySnapshotRevision().equals(readback.inventorySnapshotRevision())
        || !readback.key()
            .equals(
                CanonicalGameplayAccountInitialBarrierKey.forTarget(
                    readback.assignment(), current.initializerId(), current.originalRequestId()))) {
      throw conflict("Physical INITIAL OPEN readback differs from the original SQL initializer");
    }
    Map<String, String> fields = readback.barrierFields();
    if (!current.initializerId().toString().equals(fields.get("ownerOperationId"))
        || !current.accountId().toString().equals(fields.get("accountId"))
        || !current.sourceControlEpoch().toString().equals(fields.get("ownerFenceEpoch"))
        || !current.tenantId().toString().equals(fields.get("tenantId"))
        || !current.playableStateNamespaceId()
            .toString()
            .equals(fields.get("playableStateNamespaceId"))
        || !readback.assignment().tenantGameplayTag().equals(fields.get("tenantGameplayTag"))) {
      throw conflict("Physical INITIAL OPEN fields differ from the exact original target");
    }
  }

  private static void requireInitialObservationMatches(
      InitializerSnapshot snapshot,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation observation) {
    if (observation.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER
        || !snapshot.initializerId().equals(observation.operationId())
        || !snapshot.originalRequestId().equals(observation.requestId())
        || !snapshot.originalCallerContextId().equals(observation.originalCallerContextId())
        || snapshot.originalAttemptFence() != observation.originalAttemptFence()
        || !snapshot.accountId().equals(observation.accountId())
        || !snapshot.tenantId().equals(observation.tenantId())
        || !snapshot.playableStateNamespaceId().equals(observation.playableStateNamespaceId())
        || !NO_ACTIVE.equals(observation.sourceControlState())
        || !snapshot.sourceControlEpoch().toString().equals(observation.sourceControlEpoch())
        || !snapshot.inventorySnapshotRevision().toString()
            .equals(observation.inventorySnapshotRevision())
        || !snapshot.accountLoginEvidenceDigest().equals(observation.accountLoginEvidenceDigest())
        || !snapshot.sourceControlDigest().equals(observation.sourceControlDigest())
        || !snapshot.targetDigest().equals(observation.barrierTargetDigest())) {
      throw conflict("Protected INITIAL observation differs from original SQL source context");
    }
  }

  private static void requireOriginalEvidence(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult.NonSecretEvidence evidence) {
    if (evidence == null
        || !original.requestId().equals(evidence.requestId())
        || !original.callerContextId().equals(evidence.callerContextId())
        || original.expectedAttemptFence() <= 0L
        || !net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.PROFILE
            .equals(evidence.tokenProfile())
        || evidence.accountId() == null
        || evidence.tokenJti() == null
        || evidence.tokenSha256() == null
        || !evidence.tokenSha256().matches("[0-9a-f]{64}")
        || !positiveDecimal(evidence.tokenGeneration())
        || !positiveDecimal(evidence.issuanceFence())) {
      throw conflict("Authenticated Account LOGIN result differs from the exact original attempt");
    }
  }

  private static void requireExactOriginalLogin(
      DSLContext transaction,
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult.NonSecretEvidence evidence) {
    Record row =
        transaction.fetchOne(
            "SELECT caller_context_id, expected_attempt_fence, abandoned, verified_token_jti,"
                + " verified_token_sha256, verified_token_generation, verified_issuance_fence"
                + " FROM "
                + ORIGINAL
                + " WHERE request_id = ? FOR UPDATE",
            original.requestId());
    if (row == null
        || !original.callerContextId().equals(row.get("caller_context_id", UUID.class))
        || !Long.valueOf(original.expectedAttemptFence())
            .equals(row.get("expected_attempt_fence", Long.class))
        || !Boolean.FALSE.equals(row.get("abandoned", Boolean.class))
        || !evidence.tokenJti().equals(row.get("verified_token_jti", UUID.class))
        || !evidence.tokenSha256().equals(row.get("verified_token_sha256", String.class))
        || !Long.valueOf(Long.parseLong(evidence.tokenGeneration()))
            .equals(row.get("verified_token_generation", Long.class))
        || !Long.valueOf(Long.parseLong(evidence.issuanceFence()))
            .equals(row.get("verified_issuance_fence", Long.class))) {
      throw conflict("Original Account COMMITTED evidence is not retained by the LOGIN journal");
    }
  }

  private static void requireOriginalJournalStillCurrent(
      DSLContext transaction, InitializerSnapshot snapshot) {
    Record row =
        transaction.fetchOne(
            "SELECT caller_context_id, expected_attempt_fence, abandoned"
                + " FROM "
                + ORIGINAL
                + " WHERE request_id = ?",
            snapshot.originalRequestId());
    if (row == null
        || !snapshot.originalCallerContextId().equals(row.get("caller_context_id", UUID.class))
        || !Long.valueOf(snapshot.originalAttemptFence())
            .equals(row.get("expected_attempt_fence", Long.class))
        || !Boolean.FALSE.equals(row.get("abandoned", Boolean.class))) {
      throw conflict("Original INITIAL LOGIN was abandoned or its coordinates changed");
    }
  }

  private static void requireSameOriginalTarget(
      InitializerSnapshot snapshot,
      InitialGameplayLoginAttemptCoordinates original,
      UUID accountId,
      UUID tenantId,
      UUID namespaceId,
      String accountEvidenceDigest) {
    if (!snapshot.originalRequestId().equals(original.requestId())
        || !snapshot.originalCallerContextId().equals(original.callerContextId())
        || snapshot.originalAttemptFence() != original.expectedAttemptFence()
        || !snapshot.accountId().equals(accountId)
        || !snapshot.tenantId().equals(tenantId)
        || !snapshot.playableStateNamespaceId().equals(namespaceId)
        || !snapshot.accountLoginEvidenceDigest().equals(accountEvidenceDigest)) {
      throw conflict("INITIAL original request was reused for a different Account or target");
    }
  }

  private static void requireSameCurrentSource(
      InitializerSnapshot snapshot, AccountControlSource current) {
    if (!NO_ACTIVE.equals(snapshot.sourceControlState())
        || snapshot.sourceControlEpoch().signum() < 0
        || !snapshot.sourceControlEpoch().equals(current.epoch())
        || !snapshot.sourceControlDigest().equals(current.digest())
        || !snapshot.accountId().equals(current.accountId())) {
      throw conflict("INITIAL source-control tuple is no longer the exact positive NO_ACTIVE row");
    }
  }

  private static InitialObservationContext context(InitializerSnapshot snapshot) {
    return InitialObservationContext.from(snapshot);
  }

  private static BigInteger lockInventoryRevision(DSLContext transaction) {
    Record row =
        transaction.fetchOne(
            "SELECT inventory_revision FROM " + INVENTORY_CLOCK + " WHERE singleton_id = 1 FOR UPDATE");
    if (row == null) throw conflict("Canonical binding inventory clock is missing");
    BigInteger revision = readBigInteger(row, "inventory_revision");
    if (revision.signum() <= 0) throw conflict("Initial account barrier inventory revision is not positive");
    return revision;
  }

  private static Record lockExistingControl(DSLContext transaction, UUID accountId) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE account_id = ? FOR UPDATE", accountId);
    if (row == null) throw conflict("Account coverage control row is missing; absence is not source");
    return row;
  }

  private static AccountControlSource readCurrentNoActiveControl(
      DSLContext transaction, UUID accountId) {
    Record row = transaction.fetchOne("SELECT * FROM " + CONTROL + " WHERE account_id = ?", accountId);
    if (row == null) throw conflict("Account coverage control row is missing; absence is not source");
    return readNoActiveControl(row, accountId);
  }

  private static AccountControlSource readNoActiveControl(Record row, UUID accountId) {
    if (!accountId.equals(row.get("account_id", UUID.class))
        || !NO_ACTIVE.equals(row.get("state", String.class))
        || row.get("current_operation_id", UUID.class) != null
        || row.get("current_fence_intent_id", UUID.class) != null
        || row.get("current_operation_fence") != null
        || row.get("account_admission_fence", UUID.class) != null
        || row.get("historical_account_admission_fence", UUID.class) != null
        || row.get("coverage_fence", UUID.class) != null
        || row.get("inventory_snapshot_revision") != null
        || row.get("coverage_generation") != null) {
      throw conflict("INITIAL source requires the complete SQL NO_ACTIVE account-control arm");
    }
    BigInteger epoch = readBigInteger(row, "last_operation_fence");
    if (epoch.signum() < 0 || epoch.toString().length() > 256) {
      throw conflict(
          "INITIAL source requires a bounded nonnegative positive-row account-control epoch");
    }
    return new AccountControlSource(accountId, NO_ACTIVE, epoch, digestControl(row));
  }

  private static String digestControl(Record row) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("accountId", row.get("account_id", UUID.class).toString());
    fields.put("state", row.get("state", String.class));
    fields.put("lastOperationFence", readBigInteger(row, "last_operation_fence").toString());
    fields.put("currentOperationId", uuidText(row, "current_operation_id"));
    fields.put("currentFenceIntentId", uuidText(row, "current_fence_intent_id"));
    fields.put("currentOperationFence", nullableInteger(row, "current_operation_fence"));
    fields.put("accountAdmissionFence", uuidText(row, "account_admission_fence"));
    fields.put("historicalAccountAdmissionFence", uuidText(row, "historical_account_admission_fence"));
    fields.put("coverageFence", uuidText(row, "coverage_fence"));
    fields.put("inventorySnapshotRevision", nullableInteger(row, "inventory_snapshot_revision"));
    fields.put("coverageGeneration", nullableInteger(row, "coverage_generation"));
    try {
      return digest(net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(fields)));
    } catch (Exception invalid) {
      throw conflict("Account control source digest could not be computed");
    }
  }

  private static String digestAccountEvidence(CanonicalGameplayLoginResult.NonSecretEvidence e) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("accountId", e.accountId().toString());
    fields.put("requestId", e.requestId().toString());
    fields.put("callerContextId", e.callerContextId().toString());
    fields.put("tokenJti", e.tokenJti().toString());
    fields.put("tokenSha256", e.tokenSha256());
    fields.put("tokenProfile", e.tokenProfile());
    fields.put("issuedAtEpochSecond", e.issuedAtEpochSecond());
    fields.put("notBeforeEpochSecond", e.notBeforeEpochSecond());
    fields.put("expiresAtEpochSecond", e.expiresAtEpochSecond());
    fields.put("tokenGeneration", e.tokenGeneration());
    fields.put("issuanceFence", e.issuanceFence());
    fields.put("authorityTupleCanonicalJson", e.authorityTupleCanonicalJson().toStringUtf8());
    fields.put("outboxCheckpointsCanonicalJson", e.outboxCheckpointsCanonicalJson().toStringUtf8());
    fields.put(
        "outboxSourceEventEvidenceCanonicalJson",
        e.outboxSourceEventEvidenceCanonicalJson().toStringUtf8());
    try {
      return digest(net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(fields)));
    } catch (Exception invalid) {
      throw conflict("Authenticated Account response evidence digest could not be computed");
    }
  }

  private static String digestTarget(InitializerSnapshot snapshot) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("initializerId", snapshot.initializerId().toString());
    fields.put("originalRequestId", snapshot.originalRequestId().toString());
    fields.put("originalCallerContextId", snapshot.originalCallerContextId().toString());
    fields.put("originalAttemptFence", snapshot.originalAttemptFence());
    fields.put("accountId", snapshot.accountId().toString());
    fields.put("tenantId", snapshot.tenantId().toString());
    fields.put("playableStateNamespaceId", snapshot.playableStateNamespaceId().toString());
    fields.put("accountLoginEvidenceDigest", snapshot.accountLoginEvidenceDigest());
    fields.put("sourceControlState", snapshot.sourceControlState());
    fields.put("sourceControlEpoch", snapshot.sourceControlEpoch().toString());
    fields.put("sourceControlDigest", snapshot.sourceControlDigest());
    fields.put("inventorySnapshotRevision", snapshot.inventorySnapshotRevision().toString());
    return digestUnchecked(fields);
  }

  private static String digestFields(Map<String, String> fields) {
    return digestUnchecked(new java.util.TreeMap<>(fields));
  }

  private static String digestUnchecked(Object fields) {
    try {
      return digest(net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(fields)));
    } catch (Exception invalid) {
      throw conflict("Canonical INITIAL source digest could not be computed");
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String uuidText(Record row, String name) {
    UUID value = row.get(name, UUID.class);
    return value == null ? null : value.toString();
  }

  private static String nullableInteger(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    return value == null ? null : value.toBigIntegerExact().toString();
  }

  private static Record readInitializer(DSLContext transaction, UUID requestId, boolean forUpdate) {
    return transaction.fetchOne(
        "SELECT i.initializer_id, i.original_request_id, i.original_caller_context_id,"
            + " i.original_attempt_fence, i.account_id, i.account_login_evidence_digest,"
            + " i.source_control_state, i.source_control_epoch, i.inventory_snapshot_revision,"
            + " i.source_control_digest, t.tenant_id, t.playable_state_namespace_id,"
            + " t.target_state, t.before_accepted_attestation_digest, t.before_incarnation_digest,"
            + " t.before_source_payload_digest, t.before_observation_issued_at_epoch_millis,"
            + " t.before_observation_observed_at_epoch_millis,"
            + " t.before_observation_expires_at_epoch_millis, t.installed_fields_digest"
            + " FROM "
            + INITIALIZER
            + " i JOIN "
            + TARGET
            + " t ON t.initializer_id = i.initializer_id AND t.original_request_id = i.original_request_id"
            + " WHERE i.original_request_id = ?"
            + (forUpdate ? " FOR UPDATE OF i, t" : ""),
        requestId);
  }

  private static InitializerSnapshot readRequiredCurrent(
      DSLContext transaction, UUID requestId, boolean forUpdate) {
    Record row = readInitializer(transaction, requestId, forUpdate);
    if (row == null) throw conflict("Exact original INITIAL account target is missing");
    return toSnapshot(row);
  }

  private static InitializerSnapshot toSnapshot(Record row) {
    UUID requestId = row.get("original_request_id", UUID.class);
    UUID callerContextId = row.get("original_caller_context_id", UUID.class);
    UUID accountId = row.get("account_id", UUID.class);
    UUID tenantId = row.get("tenant_id", UUID.class);
    UUID namespaceId = row.get("playable_state_namespace_id", UUID.class);
    BigInteger controlEpoch = readBigInteger(row, "source_control_epoch");
    BigInteger inventoryRevision = readBigInteger(row, "inventory_snapshot_revision");
    State state;
    try {
      state = State.valueOf(row.get("target_state", String.class));
    } catch (RuntimeException malformed) {
      throw conflict("Initial account barrier target state is malformed");
    }
    InitializerSnapshot snapshot =
        new InitializerSnapshot(
            row.get("initializer_id", UUID.class),
            requestId,
            callerContextId,
            row.get("original_attempt_fence", Long.class),
            accountId,
            tenantId,
            namespaceId,
            row.get("account_login_evidence_digest", String.class),
            row.get("source_control_state", String.class),
            controlEpoch,
            inventoryRevision,
            row.get("source_control_digest", String.class),
            state,
            row.get("before_accepted_attestation_digest", String.class),
            row.get("before_incarnation_digest", String.class),
            row.get("before_source_payload_digest", String.class),
            nullableLong(row, "before_observation_issued_at_epoch_millis"),
            nullableLong(row, "before_observation_observed_at_epoch_millis"),
            nullableLong(row, "before_observation_expires_at_epoch_millis"),
            row.get("installed_fields_digest", String.class));
    if (snapshot.initializerId() == null
        || snapshot.originalRequestId() == null
        || snapshot.originalCallerContextId() == null
        || snapshot.originalAttemptFence() <= 0L
        || snapshot.accountId() == null
        || snapshot.tenantId() == null
        || snapshot.playableStateNamespaceId() == null
        || !NO_ACTIVE.equals(snapshot.sourceControlState())
        || controlEpoch.signum() < 0
        || controlEpoch.toString().length() > 256
        || inventoryRevision.signum() <= 0
        || !isDigest(snapshot.accountLoginEvidenceDigest())
        || !isDigest(snapshot.sourceControlDigest())) {
      throw conflict("Persisted INITIAL account barrier identity or source is malformed");
    }
    return snapshot.withTargetDigest(digestTarget(snapshot));
  }

  private static long nullableLong(Record row, String name) {
    Long value = row.get(name, Long.class);
    return value == null ? 0L : value;
  }

  private static BigInteger readBigInteger(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    if (value == null) throw conflict("Persisted INITIAL account field is missing: " + name);
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Persisted INITIAL account field is not integral: " + name);
    }
  }

  private static boolean positiveDecimal(String value) {
    return value != null && value.matches("[1-9][0-9]{0,38}");
  }

  private static boolean isDigest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static UUID randomNonNilUuid() {
    UUID value = UUID.randomUUID();
    if (value.equals(new UUID(0L, 0L))) throw conflict("UUID source returned nil identity");
    return value;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw new IllegalArgumentException(name + " must not be nil");
  }

  private static void requireOne(int actual, String message) {
    if (actual != 1) throw conflict(message);
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }

  enum State {
    NEVER_DISPATCHED,
    DISPATCH_POSSIBLE,
    OPEN_INSTALLED
  }

  record InitialObservationContext(
      UUID initializerId,
      UUID originalRequestId,
      UUID originalCallerContextId,
      long originalAttemptFence,
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String sourceControlState,
      BigInteger sourceControlEpoch,
      BigInteger inventorySnapshotRevision,
      String accountLoginEvidenceDigest,
      String sourceControlDigest,
      String targetDigest) {
    private static InitialObservationContext from(InitializerSnapshot snapshot) {
      return new InitialObservationContext(
          snapshot.initializerId(),
          snapshot.originalRequestId(),
          snapshot.originalCallerContextId(),
          snapshot.originalAttemptFence(),
          snapshot.accountId(),
          snapshot.tenantId(),
          snapshot.playableStateNamespaceId(),
          snapshot.sourceControlState(),
          snapshot.sourceControlEpoch(),
          snapshot.inventorySnapshotRevision(),
          snapshot.accountLoginEvidenceDigest(),
          snapshot.sourceControlDigest(),
          snapshot.targetDigest());
    }
  }

  record InitializerSnapshot(
      UUID initializerId,
      UUID originalRequestId,
      UUID originalCallerContextId,
      long originalAttemptFence,
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String accountLoginEvidenceDigest,
      String sourceControlState,
      BigInteger sourceControlEpoch,
      BigInteger inventorySnapshotRevision,
      String sourceControlDigest,
      State state,
      String beforeAcceptedAttestationDigest,
      String beforeIncarnationDigest,
      String beforeSourcePayloadDigest,
      long beforeIssuedAtEpochMillis,
      long beforeObservedAtEpochMillis,
      long beforeExpiresAtEpochMillis,
      String installedFieldsDigest,
      String targetDigest) {
    private InitializerSnapshot(
        UUID initializerId,
        UUID originalRequestId,
        UUID originalCallerContextId,
        long originalAttemptFence,
        UUID accountId,
        UUID tenantId,
        UUID playableStateNamespaceId,
        String accountLoginEvidenceDigest,
        String sourceControlState,
        BigInteger sourceControlEpoch,
        BigInteger inventorySnapshotRevision,
        String sourceControlDigest,
        State state,
        String beforeAcceptedAttestationDigest,
        String beforeIncarnationDigest,
        String beforeSourcePayloadDigest,
        long beforeIssuedAtEpochMillis,
        long beforeObservedAtEpochMillis,
        long beforeExpiresAtEpochMillis,
        String installedFieldsDigest) {
      this(
          initializerId,
          originalRequestId,
          originalCallerContextId,
          originalAttemptFence,
          accountId,
          tenantId,
          playableStateNamespaceId,
          accountLoginEvidenceDigest,
          sourceControlState,
          sourceControlEpoch,
          inventorySnapshotRevision,
          sourceControlDigest,
          state,
          beforeAcceptedAttestationDigest,
          beforeIncarnationDigest,
          beforeSourcePayloadDigest,
          beforeIssuedAtEpochMillis,
          beforeObservedAtEpochMillis,
          beforeExpiresAtEpochMillis,
          installedFieldsDigest,
          null);
    }

    InitializerSnapshot withTargetDigest(String value) {
      return new InitializerSnapshot(
          initializerId,
          originalRequestId,
          originalCallerContextId,
          originalAttemptFence,
          accountId,
          tenantId,
          playableStateNamespaceId,
          accountLoginEvidenceDigest,
          sourceControlState,
          sourceControlEpoch,
          inventorySnapshotRevision,
          sourceControlDigest,
          state,
          beforeAcceptedAttestationDigest,
          beforeIncarnationDigest,
          beforeSourcePayloadDigest,
          beforeIssuedAtEpochMillis,
          beforeObservedAtEpochMillis,
          beforeExpiresAtEpochMillis,
          installedFieldsDigest,
          value);
    }
  }

  private record AccountControlSource(
      UUID accountId, String state, BigInteger epoch, String digest) {}
}
