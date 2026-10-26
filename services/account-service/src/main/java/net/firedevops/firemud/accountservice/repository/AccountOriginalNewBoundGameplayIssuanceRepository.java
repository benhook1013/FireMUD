package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable Account owner for the single ORIGINAL-NEW bound-token lineage attached to one committed
 * Account admission lease. LOGIN issuance remains in its existing table and has no changed shape.
 *
 * <p>This repository only records exact owner-produced identity and postcondition evidence. It does
 * not sign credentials, establish the Game Session final-admission result, or make a handle
 * consumable without the caller's independent current-source and authenticated-read checks.
 */
public final class AccountOriginalNewBoundGameplayIssuanceRepository {
  private static final String TABLE = "account_original_new_bound_gameplay_issuances";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;

  public AccountOriginalNewBoundGameplayIssuanceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "Account DSLContext is required");
  }

  /**
   * Creates one fresh generation-1 lineage or returns that exact lineage for a matching lease.
   * Generated IDs and token times are inputs used only for a new row; a retry always returns the
   * originally persisted IDs and times and never starts another lineage.
   */
  public Genesis beginGenesis(
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      UUID operationId,
      UUID tokenJti,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(lease, "Exact original Account lease is required");
    Objects.requireNonNull(receipt, "Durable original Account ACK receipt is required");
    requireUuidV4(operationId);
    requireUuidV4(tokenJti);
    if (operationId.equals(tokenJti)
        || issuedAtEpochSecond <= 0L
        || notBeforeEpochSecond <= 0L
        || notBeforeEpochSecond > issuedAtEpochSecond
        || expiresAtEpochSecond <= issuedAtEpochSecond
        || expiresAtEpochSecond - issuedAtEpochSecond
            > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
      throw unavailable();
    }

    Map<String, Object> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier.get("bindingScope"));
    UUID requestId = uuid(carrier.get("requestId"));
    UUID leaseId = uuid(carrier.get("leaseId"));
    UUID accountId = uuid(scope.get("accountId"));
    UUID bindingDecisionId = receipt.bindingDecisionId();
    long leaseFence = lease.leaseFence().longValueExact();
    long issuanceFence = positiveLong(carrier.get("issuanceFence"));
    requireOriginalAck(lease, receipt, requestId, accountId, leaseId, leaseFence);

    byte[] scopeBytes = canonicalBytes(scope);
    String bindingScopeSha256 = sha256(scopeBytes);
    byte[] tupleBytes = canonicalBytes(carrier.get("authorityTuple"));
    Map<?, ?> baseline = object(carrier.get("membershipBaseline"));
    byte[] membershipBaselineBytes = canonicalBytes(baseline);
    byte[] membershipVersionBytes = canonicalBytes(baseline.get("membershipVersion"));
    byte[] checkpointsBytes = canonicalBytes(carrier.get("outboxCheckpoints"));
    String requestDigest =
        requestDigest(
            lease,
            receipt,
            scopeBytes,
            tupleBytes,
            membershipBaselineBytes,
            membershipVersionBytes,
            checkpointsBytes);
    try {
      // Account first, then the original lease/receipt rows, then this lineage row. The trigger
      // independently checks the exact committed operation and separate ACK receipt on INSERT.
      dsl.fetchOne(
          "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", accountId);
      Record retained =
          dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE lease_id = ? FOR UPDATE", leaseId);
      if (retained != null) {
        Genesis exact = decode(retained);
        requireSameFrozenSource(
            exact,
            requestId,
            accountId,
            leaseId,
            leaseFence,
            bindingDecisionId,
            lease,
            receipt,
            requestDigest,
            bindingScopeSha256,
            scopeBytes,
            tupleBytes,
            membershipBaselineBytes,
            membershipVersionBytes,
            checkpointsBytes,
            issuanceFence);
        return exact;
      }
      Record priorBinding =
          dsl.fetchOne(
              "SELECT * FROM " + TABLE + " WHERE binding_scope_sha256 = ? FOR UPDATE",
              bindingScopeSha256);
      if (priorBinding != null) throw unavailable();

      int inserted =
          dsl.execute(
              "INSERT INTO "
                  + TABLE
                  + " (operation_id, request_id, account_uuid, caller_workload, request_digest, "
                  + "lease_id, lease_fence, binding_decision_id, binding_scope_sha256, "
                  + "original_lease_evidence_sha256, "
                  + "original_ack_receipt_xid, original_ack_evidence_sha256, "
                  + "binding_scope_canonical_bytes, authority_tuple_canonical_bytes, "
                  + "membership_baseline_canonical_bytes, membership_version_canonical_bytes, "
                  + "outbox_checkpoints_canonical_bytes, "
                  + "issuance_fence, token_jti, token_generation, issued_at_epoch_second, "
                  + "not_before_epoch_second, expires_at_epoch_second) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?) "
                  + "ON CONFLICT DO NOTHING",
              operationId,
              requestId,
              accountId,
              carrier.get("callerWorkload"),
              requestDigest,
              leaseId,
              leaseFence,
              bindingDecisionId,
              bindingScopeSha256,
              lease.sha256(),
              receipt.receiptXid(),
              receipt.evidenceSha256(),
              scopeBytes,
              tupleBytes,
              membershipBaselineBytes,
              membershipVersionBytes,
              checkpointsBytes,
              issuanceFence,
              tokenJti,
              issuedAtEpochSecond,
              notBeforeEpochSecond,
              expiresAtEpochSecond);
      if (inserted != 1) throw unavailable();
      Record readback =
          dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE lease_id = ? FOR UPDATE", leaseId);
      if (readback == null) throw unavailable();
      Genesis created = decode(readback);
      requireSameFrozenSource(
          created,
          requestId,
          accountId,
          leaseId,
          leaseFence,
          bindingDecisionId,
          lease,
          receipt,
          requestDigest,
          bindingScopeSha256,
          scopeBytes,
          tupleBytes,
          membershipBaselineBytes,
          membershipVersionBytes,
          checkpointsBytes,
          issuanceFence);
      if (!created.lineageId().equals(operationId)
          || !created.tokenJti().equals(tokenJti)
          || created.issuedAtEpochSecond() != issuedAtEpochSecond
          || created.notBeforeEpochSecond() != notBeforeEpochSecond
          || created.expiresAtEpochSecond() != expiresAtEpochSecond) throw unavailable();
      return created;
    } finally {
      wipe(scopeBytes);
      wipe(tupleBytes);
      wipe(membershipBaselineBytes);
      wipe(membershipVersionBytes);
      wipe(checkpointsBytes);
    }
  }

  /** Binds the exact signed candidate, opaque-handle hash, and encrypted result once. */
  public Genesis bindSignedResult(
      Genesis genesis,
      String tokenHash,
      String signerKid,
      String signerGeneration,
      byte[] canonicalPendingRegistryRecord,
      String opaqueHandleHash,
      byte[] encryptedResultEnvelope) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(genesis);
    requireSha256(tokenHash);
    requireSha256(opaqueHandleHash);
    if (signerKid == null || !signerKid.matches("[A-Za-z0-9_-]{1,64}")) throw unavailable();
    requirePositiveDecimal(signerGeneration);
    requireBytes(canonicalPendingRegistryRecord, 16 * 1024);
    requireBytes(encryptedResultEnvelope, 65_610);
    String envelopeHash = sha256(encryptedResultEnvelope);

    Record prior = requireByLeaseForUpdate(genesis.leaseId());
    Genesis current = decode(prior);
    requireSameGenesis(genesis, current);
    if (current.tokenHash() != null) {
      if (!tokenHash.equals(current.tokenHash())
          || !signerKid.equals(current.signerKid())
          || !signerGeneration.equals(current.signerGeneration())
          || !Arrays.equals(canonicalPendingRegistryRecord, current.pendingRegistryRecordBytes())
          || !opaqueHandleHash.equals(current.rebindHandleSha256())
          || !envelopeHash.equals(current.encryptedResultEnvelopeSha256())
          || !Arrays.equals(encryptedResultEnvelope, current.encryptedResultEnvelopeBytes())) {
        throw unavailable();
      }
      return current;
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET token_hash = ?, signer_kid = ?, signer_generation = ?, "
                + "pending_registry_record_bytes = ?, rebind_handle_sha256 = ?, "
                + "encrypted_result_envelope_sha256 = ?, encrypted_result_envelope_bytes = ? "
                + "WHERE operation_id = ? AND status = 'PENDING' AND token_hash IS NULL",
            tokenHash,
            signerKid,
            signerGeneration,
            canonicalPendingRegistryRecord,
            opaqueHandleHash,
            envelopeHash,
            encryptedResultEnvelope,
            genesis.lineageId());
    if (changed != 1) throw unavailable();
    Genesis readback = decode(requireByLeaseForUpdate(genesis.leaseId()));
    requireSameGenesis(genesis, readback);
    if (!tokenHash.equals(readback.tokenHash())
        || !opaqueHandleHash.equals(readback.rebindHandleSha256())
        || !envelopeHash.equals(readback.encryptedResultEnvelopeSha256())
        || !Arrays.equals(canonicalPendingRegistryRecord, readback.pendingRegistryRecordBytes())
        || !Arrays.equals(encryptedResultEnvelope, readback.encryptedResultEnvelopeBytes())) {
      throw unavailable();
    }
    return readback;
  }

  /** Finalizes only the existing one-way issuance after exact pending-registry readback. */
  public Genesis commit(Genesis genesis, String pendingRegistryReceiptSha256) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(genesis);
    requireSha256(pendingRegistryReceiptSha256);
    Genesis current = decode(requireByLeaseForUpdate(genesis.leaseId()));
    requireSameGenesis(genesis, current);
    if (current.status() == Status.COMMITTED) {
      if (!pendingRegistryReceiptSha256.equals(current.committedRegistryReceiptSha256())) {
        throw unavailable();
      }
      return current;
    }
    if (current.tokenHash() == null) throw unavailable();
    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'COMMITTED', committed_registry_receipt_sha256 = ?, "
                + "committed_at = CURRENT_TIMESTAMP WHERE operation_id = ? AND status = 'PENDING' "
                + "AND token_hash = ? AND rebind_handle_sha256 = ?",
            pendingRegistryReceiptSha256,
            genesis.lineageId(),
            current.tokenHash(),
            current.rebindHandleSha256());
    if (changed != 1) throw unavailable();
    Genesis committed = decode(requireByLeaseForUpdate(genesis.leaseId()));
    if (committed.status() != Status.COMMITTED
        || !pendingRegistryReceiptSha256.equals(committed.committedRegistryReceiptSha256())) {
      throw unavailable();
    }
    return committed;
  }

  /** Readback by exact original lease; this does not authenticate the supplied lease or caller. */
  public Genesis readByLease(UUID leaseId) {
    requireReadableTransaction();
    requireUuidV4(leaseId);
    Record row = dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE lease_id = ?", leaseId);
    return row == null ? null : decode(row);
  }

  /** Stores the actual Account-authenticated Game Session final-readback anchors once. */
  Genesis confirmFinalAdmission(
      Genesis expected,
      long actualAdmissionAtMillis,
      long actualContinuityExpiresAtMillis,
      String authenticatedReadbackSha256) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(expected);
    requireSha256(authenticatedReadbackSha256);
    if (actualAdmissionAtMillis <= 0L || actualContinuityExpiresAtMillis <= actualAdmissionAtMillis)
      throw unavailable();
    Genesis current = decode(requireByLeaseForUpdate(expected.leaseId()));
    requireSameGenesis(expected, current);
    if (current.status() != Status.COMMITTED) throw unavailable();
    if (current.confirmedAdmissionAtMillis() != null) {
      if (current.confirmedAdmissionAtMillis() != actualAdmissionAtMillis
          || current.confirmedContinuityExpiresAtMillis() != actualContinuityExpiresAtMillis
          || !authenticatedReadbackSha256.equals(current.confirmationReadbackSha256())) {
        throw unavailable();
      }
      return current;
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET confirmed_admission_at_ms = ?, confirmed_continuity_expires_at_ms = ?, "
                + "confirmation_readback_sha256 = ? WHERE operation_id = ? AND status = 'COMMITTED' "
                + "AND confirmed_admission_at_ms IS NULL AND handle_consumed_at_ms IS NULL",
            actualAdmissionAtMillis,
            actualContinuityExpiresAtMillis,
            authenticatedReadbackSha256,
            expected.lineageId());
    if (changed != 1) throw unavailable();
    Genesis stored = decode(requireByLeaseForUpdate(expected.leaseId()));
    if (!Objects.equals(stored.confirmedAdmissionAtMillis(), actualAdmissionAtMillis)
        || !Objects.equals(
            stored.confirmedContinuityExpiresAtMillis(), actualContinuityExpiresAtMillis)
        || !authenticatedReadbackSha256.equals(stored.confirmationReadbackSha256())) {
      throw unavailable();
    }
    return stored;
  }

  /** Atomically consumes the Account-only handle once inside the already currentness-checked tx. */
  Genesis consumeOneUseHandle(
      Genesis expected,
      String presentedHandleSha256,
      UUID consumptionRequestId,
      long actualAdmissionAtMillis) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(expected);
    requireSha256(presentedHandleSha256);
    requireUuidV4(consumptionRequestId);
    if (actualAdmissionAtMillis <= 0L) throw unavailable();
    Genesis current = decode(requireByLeaseForUpdate(expected.leaseId()));
    requireSameGenesis(expected, current);
    if (current.status() != Status.COMMITTED
        || current.confirmedAdmissionAtMillis() == null
        || current.confirmedContinuityExpiresAtMillis() == null
        || current.confirmationReadbackSha256() == null
        || current.confirmedAdmissionAtMillis() != actualAdmissionAtMillis
        || !presentedHandleSha256.equals(current.rebindHandleSha256())) throw unavailable();
    if (current.handleConsumedAtMillis() != null) {
      if (!consumptionRequestId.equals(current.handleConsumptionRequestId())
          || current.handleConsumedAtMillis() >= current.confirmedContinuityExpiresAtMillis()) {
        throw unavailable();
      }
      return current;
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET handle_consumed_at_ms = floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint, "
                + "handle_consumption_request_id = ? WHERE operation_id = ? AND status = 'COMMITTED' "
                + "AND rebind_handle_sha256 = ? AND confirmed_admission_at_ms = ? "
                + "AND confirmed_continuity_expires_at_ms > floor(extract(epoch FROM clock_timestamp()) * 1000) "
                + "AND handle_consumed_at_ms IS NULL",
            consumptionRequestId,
            expected.lineageId(),
            presentedHandleSha256,
            actualAdmissionAtMillis);
    if (changed != 1) throw unavailable();
    Genesis consumed = decode(requireByLeaseForUpdate(expected.leaseId()));
    if (!consumptionRequestId.equals(consumed.handleConsumptionRequestId())
        || consumed.handleConsumedAtMillis() == null
        || consumed.handleConsumedAtMillis() < actualAdmissionAtMillis
        || consumed.handleConsumedAtMillis() >= consumed.confirmedContinuityExpiresAtMillis()) {
      throw unavailable();
    }
    return consumed;
  }

  private Record requireByLeaseForUpdate(UUID leaseId) {
    Record row = dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE lease_id = ? FOR UPDATE", leaseId);
    if (row == null) throw unavailable();
    return row;
  }

  private static void requireOriginalAck(
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      UUID requestId,
      UUID accountId,
      UUID leaseId,
      long leaseFence) {
    if (lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || receipt.operation().state() != State.COMMITTED
        || !requestId.equals(receipt.requestId())
        || !accountId.equals(receipt.accountId())
        || !leaseId.equals(receipt.leaseId())
        || leaseFence != receipt.leaseFence()
        || !lease.sha256().equals(receipt.evidenceSha256())
        || !receipt.bindingDecisionId().equals(receipt.operation().bindingDecisionId())
        || !receipt.operation().evidence().hasSameIdentity(lease)) throw unavailable();
  }

  private static void requireSameFrozenSource(
      Genesis genesis,
      UUID requestId,
      UUID accountId,
      UUID leaseId,
      long leaseFence,
      UUID decisionId,
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      String requestDigest,
      String bindingScopeSha256,
      byte[] scope,
      byte[] tuple,
      byte[] membershipBaseline,
      byte[] membershipVersion,
      byte[] checkpoints,
      long issuanceFence) {
    if (!genesis.requestId().equals(requestId)
        || !genesis.accountId().equals(accountId)
        || !genesis.leaseId().equals(leaseId)
        || genesis.leaseFence() != leaseFence
        || !genesis.bindingDecisionId().equals(decisionId)
        || !genesis.bindingScopeSha256().equals(bindingScopeSha256)
        || !genesis.originalLeaseEvidenceSha256().equals(lease.sha256())
        || !genesis.originalAckReceiptXid().equals(receipt.receiptXid())
        || !genesis.originalAckEvidenceSha256().equals(receipt.evidenceSha256())
        || !genesis.requestDigest().equals(requestDigest)
        || !Arrays.equals(scope, genesis.bindingScopeCanonicalBytes())
        || !Arrays.equals(tuple, genesis.authorityTupleCanonicalBytes())
        || !Arrays.equals(membershipBaseline, genesis.membershipBaselineCanonicalBytes())
        || !Arrays.equals(membershipVersion, genesis.membershipVersionCanonicalBytes())
        || !Arrays.equals(checkpoints, genesis.outboxCheckpointsCanonicalBytes())
        || genesis.issuanceFence() != issuanceFence) throw unavailable();
  }

  private static void requireSameGenesis(Genesis expected, Genesis actual) {
    if (!expected.lineageId().equals(actual.lineageId())
        || !expected.requestId().equals(actual.requestId())
        || !expected.accountId().equals(actual.accountId())
        || !expected.leaseId().equals(actual.leaseId())
        || expected.leaseFence() != actual.leaseFence()
        || !expected.bindingDecisionId().equals(actual.bindingDecisionId())
        || !expected.requestDigest().equals(actual.requestDigest())
        || !expected.tokenJti().equals(actual.tokenJti())
        || expected.issuanceFence() != actual.issuanceFence()) throw unavailable();
  }

  private static Genesis decode(Record row) {
    try {
      return new Genesis(
          row.get("operation_id", UUID.class),
          row.get("request_id", UUID.class),
          row.get("account_uuid", UUID.class),
          row.get("caller_workload", String.class),
          row.get("request_digest", String.class),
          row.get("lease_id", UUID.class),
          row.get("lease_fence", Long.class),
          row.get("binding_decision_id", UUID.class),
          row.get("binding_scope_sha256", String.class),
          row.get("original_lease_evidence_sha256", String.class),
          row.get("original_ack_receipt_xid", String.class),
          row.get("original_ack_evidence_sha256", String.class),
          row.get("binding_scope_canonical_bytes", byte[].class),
          row.get("authority_tuple_canonical_bytes", byte[].class),
          row.get("membership_baseline_canonical_bytes", byte[].class),
          row.get("membership_version_canonical_bytes", byte[].class),
          row.get("outbox_checkpoints_canonical_bytes", byte[].class),
          row.get("issuance_fence", Long.class),
          row.get("token_jti", UUID.class),
          row.get("token_generation", Long.class),
          row.get("issued_at_epoch_second", Long.class),
          row.get("not_before_epoch_second", Long.class),
          row.get("expires_at_epoch_second", Long.class),
          Status.valueOf(row.get("status", String.class)),
          row.get("token_hash", String.class),
          row.get("signer_kid", String.class),
          row.get("signer_generation", String.class),
          row.get("pending_registry_record_bytes", byte[].class),
          row.get("rebind_handle_sha256", String.class),
          row.get("encrypted_result_envelope_sha256", String.class),
          row.get("encrypted_result_envelope_bytes", byte[].class),
          row.get("committed_registry_receipt_sha256", String.class),
          row.get("confirmed_admission_at_ms", Long.class),
          row.get("confirmed_continuity_expires_at_ms", Long.class),
          row.get("confirmation_readback_sha256", String.class),
          row.get("handle_consumed_at_ms", Long.class),
          row.get("handle_consumption_request_id", UUID.class));
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static byte[] canonicalBytes(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception malformed) {
      throw unavailable();
    }
  }

  private static String requestDigest(
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      byte[] scope,
      byte[] tuple,
      byte[] membershipBaseline,
      byte[] membershipVersion,
      byte[] checkpoints) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("domain", "account-original-new-bound-gameplay/v1");
    fields.put("requestId", receipt.requestId().toString());
    fields.put("leaseId", receipt.leaseId().toString());
    fields.put("leaseFence", Long.toString(receipt.leaseFence()));
    fields.put("bindingDecisionId", receipt.bindingDecisionId().toString());
    fields.put("leaseEvidenceSha256", lease.sha256());
    fields.put("ackReceiptXid", receipt.receiptXid());
    fields.put("ackEvidenceSha256", receipt.evidenceSha256());
    fields.put("bindingScope", decodeUtf8(scope));
    fields.put("authorityTuple", decodeUtf8(tuple));
    fields.put("membershipBaseline", decodeUtf8(membershipBaseline));
    fields.put("membershipVersion", decodeUtf8(membershipVersion));
    fields.put("outboxCheckpoints", decodeUtf8(checkpoints));
    try {
      return sha256(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields)));
    } catch (Exception malformed) {
      throw unavailable();
    }
  }

  private static String decodeUtf8(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw unavailable();
    }
  }

  private static void requireSha256(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw unavailable();
  }

  private static void requirePositiveDecimal(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw unavailable();
  }

  private static void requireBytes(byte[] value, int maximum) {
    if (value == null || value.length == 0 || value.length > maximum) throw unavailable();
  }

  private static void requireUuidV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) throw unavailable();
  }

  private static UUID uuid(Object value) {
    if (!(value instanceof String text)) throw unavailable();
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.version() != 4 || parsed.variant() != 2) {
        throw unavailable();
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
  }

  private static long positiveLong(Object value) {
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) throw unavailable();
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException malformed) {
      throw unavailable();
    }
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unavailable();
    return map;
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) Arrays.fill(bytes, (byte) 0);
  }

  private static void requireWritableSerializableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(java.sql.Connection.TRANSACTION_SERIALIZABLE)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw unavailable();
    }
  }

  private static void requireReadableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original-new bound gameplay issuance is unavailable");
  }

  public enum Status {
    PENDING,
    COMMITTED
  }

  /**
   * Frozen non-secret token identity and protected result source; arrays are defensively copied.
   */
  public static final class Genesis {
    private final UUID lineageId;
    private final UUID requestId;
    private final UUID accountId;
    private final String callerWorkload;
    private final String requestDigest;
    private final UUID leaseId;
    private final long leaseFence;
    private final UUID bindingDecisionId;
    private final String bindingScopeSha256;
    private final String originalLeaseEvidenceSha256;
    private final String originalAckReceiptXid;
    private final String originalAckEvidenceSha256;
    private final byte[] bindingScopeCanonicalBytes;
    private final byte[] authorityTupleCanonicalBytes;
    private final byte[] membershipBaselineCanonicalBytes;
    private final byte[] membershipVersionCanonicalBytes;
    private final byte[] outboxCheckpointsCanonicalBytes;
    private final long issuanceFence;
    private final UUID tokenJti;
    private final long tokenGeneration;
    private final long issuedAtEpochSecond;
    private final long notBeforeEpochSecond;
    private final long expiresAtEpochSecond;
    private final Status status;
    private final String tokenHash;
    private final String signerKid;
    private final String signerGeneration;
    private final byte[] pendingRegistryRecordBytes;
    private final String rebindHandleSha256;
    private final String encryptedResultEnvelopeSha256;
    private final byte[] encryptedResultEnvelopeBytes;
    private final String committedRegistryReceiptSha256;
    private final Long confirmedAdmissionAtMillis;
    private final Long confirmedContinuityExpiresAtMillis;
    private final String confirmationReadbackSha256;
    private final Long handleConsumedAtMillis;
    private final UUID handleConsumptionRequestId;

    private Genesis(
        UUID lineageId,
        UUID requestId,
        UUID accountId,
        String callerWorkload,
        String requestDigest,
        UUID leaseId,
        long leaseFence,
        UUID bindingDecisionId,
        String bindingScopeSha256,
        String originalLeaseEvidenceSha256,
        String originalAckReceiptXid,
        String originalAckEvidenceSha256,
        byte[] bindingScopeCanonicalBytes,
        byte[] authorityTupleCanonicalBytes,
        byte[] membershipBaselineCanonicalBytes,
        byte[] membershipVersionCanonicalBytes,
        byte[] outboxCheckpointsCanonicalBytes,
        long issuanceFence,
        UUID tokenJti,
        long tokenGeneration,
        long issuedAtEpochSecond,
        long notBeforeEpochSecond,
        long expiresAtEpochSecond,
        Status status,
        String tokenHash,
        String signerKid,
        String signerGeneration,
        byte[] pendingRegistryRecordBytes,
        String rebindHandleSha256,
        String encryptedResultEnvelopeSha256,
        byte[] encryptedResultEnvelopeBytes,
        String committedRegistryReceiptSha256,
        Long confirmedAdmissionAtMillis,
        Long confirmedContinuityExpiresAtMillis,
        String confirmationReadbackSha256,
        Long handleConsumedAtMillis,
        UUID handleConsumptionRequestId) {
      this.lineageId = Objects.requireNonNull(lineageId);
      this.requestId = Objects.requireNonNull(requestId);
      this.accountId = Objects.requireNonNull(accountId);
      this.callerWorkload = Objects.requireNonNull(callerWorkload);
      this.requestDigest = Objects.requireNonNull(requestDigest);
      this.leaseId = Objects.requireNonNull(leaseId);
      this.leaseFence = leaseFence;
      this.bindingDecisionId = Objects.requireNonNull(bindingDecisionId);
      this.bindingScopeSha256 = Objects.requireNonNull(bindingScopeSha256);
      this.originalLeaseEvidenceSha256 = Objects.requireNonNull(originalLeaseEvidenceSha256);
      this.originalAckReceiptXid = Objects.requireNonNull(originalAckReceiptXid);
      this.originalAckEvidenceSha256 = Objects.requireNonNull(originalAckEvidenceSha256);
      this.bindingScopeCanonicalBytes = copy(bindingScopeCanonicalBytes);
      this.authorityTupleCanonicalBytes = copy(authorityTupleCanonicalBytes);
      this.membershipBaselineCanonicalBytes = copy(membershipBaselineCanonicalBytes);
      this.membershipVersionCanonicalBytes = copy(membershipVersionCanonicalBytes);
      this.outboxCheckpointsCanonicalBytes = copy(outboxCheckpointsCanonicalBytes);
      this.issuanceFence = issuanceFence;
      this.tokenJti = Objects.requireNonNull(tokenJti);
      this.tokenGeneration = tokenGeneration;
      this.issuedAtEpochSecond = issuedAtEpochSecond;
      this.notBeforeEpochSecond = notBeforeEpochSecond;
      this.expiresAtEpochSecond = expiresAtEpochSecond;
      this.status = Objects.requireNonNull(status);
      this.tokenHash = tokenHash;
      this.signerKid = signerKid;
      this.signerGeneration = signerGeneration;
      this.pendingRegistryRecordBytes = copy(pendingRegistryRecordBytes);
      this.rebindHandleSha256 = rebindHandleSha256;
      this.encryptedResultEnvelopeSha256 = encryptedResultEnvelopeSha256;
      this.encryptedResultEnvelopeBytes = copy(encryptedResultEnvelopeBytes);
      this.committedRegistryReceiptSha256 = committedRegistryReceiptSha256;
      this.confirmedAdmissionAtMillis = confirmedAdmissionAtMillis;
      this.confirmedContinuityExpiresAtMillis = confirmedContinuityExpiresAtMillis;
      this.confirmationReadbackSha256 = confirmationReadbackSha256;
      this.handleConsumedAtMillis = handleConsumedAtMillis;
      this.handleConsumptionRequestId = handleConsumptionRequestId;
      if ((confirmedAdmissionAtMillis == null) != (confirmedContinuityExpiresAtMillis == null)
          || (confirmedAdmissionAtMillis == null) != (confirmationReadbackSha256 == null)
          || (handleConsumedAtMillis == null) != (handleConsumptionRequestId == null)
          || (confirmedAdmissionAtMillis != null
              && (confirmedAdmissionAtMillis <= 0L
                  || confirmedContinuityExpiresAtMillis <= confirmedAdmissionAtMillis
                  || !confirmationReadbackSha256.matches("[0-9a-f]{64}")
                  || status != Status.COMMITTED))
          || (handleConsumedAtMillis != null
              && (handleConsumedAtMillis <= 0L
                  || confirmedContinuityExpiresAtMillis == null
                  || handleConsumedAtMillis >= confirmedContinuityExpiresAtMillis
                  || status != Status.COMMITTED))) {
        throw unavailable();
      }
      if (leaseFence <= 0L
          || issuanceFence <= 0L
          || tokenGeneration != 1L
          || issuedAtEpochSecond <= 0L
          || notBeforeEpochSecond <= 0L
          || notBeforeEpochSecond > issuedAtEpochSecond
          || expiresAtEpochSecond <= issuedAtEpochSecond) {
        throw unavailable();
      }
    }

    private static byte[] copy(byte[] value) {
      return value == null ? null : value.clone();
    }

    public UUID lineageId() {
      return lineageId;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID accountId() {
      return accountId;
    }

    public String callerWorkload() {
      return callerWorkload;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public UUID leaseId() {
      return leaseId;
    }

    public long leaseFence() {
      return leaseFence;
    }

    public UUID bindingDecisionId() {
      return bindingDecisionId;
    }

    public String bindingScopeSha256() {
      return bindingScopeSha256;
    }

    public String originalLeaseEvidenceSha256() {
      return originalLeaseEvidenceSha256;
    }

    public String originalAckReceiptXid() {
      return originalAckReceiptXid;
    }

    public String originalAckEvidenceSha256() {
      return originalAckEvidenceSha256;
    }

    public byte[] bindingScopeCanonicalBytes() {
      return bindingScopeCanonicalBytes.clone();
    }

    public byte[] authorityTupleCanonicalBytes() {
      return authorityTupleCanonicalBytes.clone();
    }

    public byte[] membershipBaselineCanonicalBytes() {
      return membershipBaselineCanonicalBytes.clone();
    }

    public byte[] membershipVersionCanonicalBytes() {
      return membershipVersionCanonicalBytes.clone();
    }

    public byte[] outboxCheckpointsCanonicalBytes() {
      return outboxCheckpointsCanonicalBytes.clone();
    }

    public long issuanceFence() {
      return issuanceFence;
    }

    public UUID tokenJti() {
      return tokenJti;
    }

    public long tokenGeneration() {
      return tokenGeneration;
    }

    public long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    public long notBeforeEpochSecond() {
      return notBeforeEpochSecond;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public Status status() {
      return status;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public String signerKid() {
      return signerKid;
    }

    public String signerGeneration() {
      return signerGeneration;
    }

    public byte[] pendingRegistryRecordBytes() {
      return copy(pendingRegistryRecordBytes);
    }

    public String rebindHandleSha256() {
      return rebindHandleSha256;
    }

    public String encryptedResultEnvelopeSha256() {
      return encryptedResultEnvelopeSha256;
    }

    public byte[] encryptedResultEnvelopeBytes() {
      return copy(encryptedResultEnvelopeBytes);
    }

    public String committedRegistryReceiptSha256() {
      return committedRegistryReceiptSha256;
    }

    public Long confirmedAdmissionAtMillis() {
      return confirmedAdmissionAtMillis;
    }

    public Long confirmedContinuityExpiresAtMillis() {
      return confirmedContinuityExpiresAtMillis;
    }

    public String confirmationReadbackSha256() {
      return confirmationReadbackSha256;
    }

    public Long handleConsumedAtMillis() {
      return handleConsumedAtMillis;
    }

    public UUID handleConsumptionRequestId() {
      return handleConsumptionRequestId;
    }

    @Override
    public String toString() {
      return "OriginalNewBoundGameplayGenesis[status=" + status + ",credential=redacted]";
    }
  }
}
