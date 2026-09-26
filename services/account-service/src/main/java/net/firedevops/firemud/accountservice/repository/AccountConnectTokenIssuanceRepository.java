package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Account-owned first-writer-wins storage and exact readback for connect-token issuance. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountConnectTokenIssuanceRepository {
  private static final String OPERATION_TABLE = "account_connect_token_issuance_operations";
  private static final String ENVELOPE_TABLE = "account_connect_token_response_envelopes";
  private static final int REQUEST_DIGEST_VERSION = 1;
  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TOKEN_IDENTITY_LENGTH = 128;
  private static final int MAX_OUTCOME_CODE_LENGTH = 64;
  private static final int MAX_RECONCILIATION_REASON_LENGTH = 128;

  private final DSLContext dsl;

  public AccountConnectTokenIssuanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Claims one operation identity without replacing or mutating a prior writer's digest or state.
   * PostgreSQL's unique identity constraint arbitrates racing writers; the subsequent read returns
   * the winner's durable row. A changed digest is an idempotency conflict and never inserts.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ClaimResult claim(AccountConnectTokenIssuanceIdentity identity, byte[] requestDigest) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    UUID proposedOperationId = UUID.randomUUID();
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATION_TABLE
                + " (operation_id, account_id, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING') "
                + "ON CONFLICT (account_id, tenant_id, connect_scope_hash, request_id) "
                + "DO NOTHING",
            proposedOperationId,
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest);
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Connect-token issuance claim result was ambiguous");
    }

    AccountConnectTokenIssuanceOperation operation =
        readOperation(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Connect-token issuance claim has no exact durable readback"));
    if (!MessageDigest.isEqual(operation.requestDigest(), digest)) {
      throw new IdempotencyConflictException(
          "Connect-token request identity was reused with a different digest");
    }
    if (inserted == 1 && !proposedOperationId.equals(operation.operationId())) {
      throw new IllegalStateException("Connect-token issuance claim returned another operation");
    }
    return new ClaimResult(
        inserted == 1 ? ClaimDisposition.CLAIMED : ClaimDisposition.REPLAYED, identity, operation);
  }

  /** Reads one exact operation identity and requires an exact versioned digest match if present. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountConnectTokenIssuanceOperation> find(
      AccountConnectTokenIssuanceIdentity identity, byte[] requestDigest) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    Optional<AccountConnectTokenIssuanceOperation> operation = readOperation(identity);
    if (operation.isPresent() && !MessageDigest.isEqual(operation.get().requestDigest(), digest)) {
      throw new IdempotencyConflictException(
          "Connect-token request identity was reused with a different digest");
    }
    return operation;
  }

  /**
   * Adds previously absent token/authority evidence to the first writer's still-PENDING row.
   * Existing evidence is write-once; retries may confirm the same bytes but cannot replace them.
   * This lets an ambiguous operation retain what Account already proved without making it terminal
   * or permitting a later caller to remint.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountConnectTokenIssuanceOperation recordPendingEvidence(
      ClaimResult claim,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash,
      byte[] contextEvidenceDigest,
      byte[] authorityTupleDigest,
      byte[] issuanceFenceDigest,
      byte[] postconditionDigest) {
    AccountConnectTokenIssuanceIdentity identity = requireClaimIdentity(claim);
    byte[] digest = requireDigest(requestDigest, "request digest");
    byte[] checkedTokenHash = optionalDigest(tokenHash, "token hash");
    byte[] checkedContextDigest = optionalDigest(contextEvidenceDigest, "context digest");
    byte[] checkedAuthorityDigest = optionalDigest(authorityTupleDigest, "authority tuple digest");
    byte[] checkedFenceDigest = optionalDigest(issuanceFenceDigest, "issuance-fence digest");
    byte[] checkedPostconditionDigest = optionalDigest(postconditionDigest, "postcondition digest");
    if (tokenIdentity == null
        && checkedContextDigest == null
        && checkedAuthorityDigest == null
        && checkedFenceDigest == null
        && checkedPostconditionDigest == null) {
      throw new IllegalArgumentException(
          "At least one pending connect-token evidence field is required");
    }
    if ((tokenIdentity == null) != (checkedTokenHash == null)
        || (tokenIdentity != null
            && (tokenIdentity.isBlank()
                || tokenIdentity.length() > MAX_TOKEN_IDENTITY_LENGTH
                || tokenIdentity.indexOf('\0') >= 0))) {
      throw new IllegalArgumentException("Pending connect-token identity/hash evidence is invalid");
    }

    AccountConnectTokenIssuanceOperation operation = requireClaimedOperation(claim, digest);
    if (operation.lifecycle() != Lifecycle.PENDING) {
      throw new IllegalStateException("Only a pending connect-token operation can add evidence");
    }
    requireEvidenceCompatible(
        operation,
        tokenIdentity,
        checkedTokenHash,
        checkedContextDigest,
        checkedAuthorityDigest,
        checkedFenceDigest,
        checkedPostconditionDigest);

    Record changed =
        dsl.fetchOne(
            "UPDATE "
                + OPERATION_TABLE
                + " SET token_identity = COALESCE(token_identity, ?), "
                + "token_hash = COALESCE(token_hash, ?), "
                + "context_evidence_digest = COALESCE(context_evidence_digest, ?), "
                + "authority_tuple_digest = COALESCE(authority_tuple_digest, ?), "
                + "issuance_fence_digest = COALESCE(issuance_fence_digest, ?), "
                + "postcondition_digest = COALESCE(postcondition_digest, ?) "
                + "WHERE operation_id = ? AND account_id = ? AND tenant_id = ? "
                + "AND connect_scope_hash = ? AND request_id = ? "
                + "AND request_digest_version = ? AND request_digest = ? AND status = 'PENDING' "
                + "AND (?::VARCHAR IS NULL OR token_identity IS NULL OR token_identity = ?) "
                + "AND (?::BYTEA IS NULL OR token_hash IS NULL OR token_hash = ?) "
                + "AND (?::BYTEA IS NULL OR context_evidence_digest IS NULL "
                + "OR context_evidence_digest = ?) "
                + "AND (?::BYTEA IS NULL OR authority_tuple_digest IS NULL "
                + "OR authority_tuple_digest = ?) "
                + "AND (?::BYTEA IS NULL OR issuance_fence_digest IS NULL "
                + "OR issuance_fence_digest = ?) "
                + "AND (?::BYTEA IS NULL OR postcondition_digest IS NULL OR postcondition_digest = ?) "
                + "RETURNING operation_id",
            tokenIdentity,
            checkedTokenHash,
            checkedContextDigest,
            checkedAuthorityDigest,
            checkedFenceDigest,
            checkedPostconditionDigest,
            operation.operationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest,
            tokenIdentity,
            tokenIdentity,
            checkedTokenHash,
            checkedTokenHash,
            checkedContextDigest,
            checkedContextDigest,
            checkedAuthorityDigest,
            checkedAuthorityDigest,
            checkedFenceDigest,
            checkedFenceDigest,
            checkedPostconditionDigest,
            checkedPostconditionDigest);
    if (changed == null) {
      throw new EvidenceMismatchException(
          "Pending connect-token operation already contains different evidence");
    }
    return readOperation(identity)
        .filter(updated -> updated.operationId().equals(operation.operationId()))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Pending connect-token evidence has no exact operation readback"));
  }

  /** Records one compare-and-increment reconciliation attempt under the supplied caller cap. */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountConnectTokenIssuanceOperation recordReconciliationAttempt(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      int expectedAttemptCount,
      int maxAttempts,
      Instant attemptedAt,
      String reason,
      Instant nextAttemptAt) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    if (expectedAttemptCount < 0
        || maxAttempts < 1
        || expectedAttemptCount >= maxAttempts
        || attemptedAt == null
        || nextAttemptAt == null
        || nextAttemptAt.isBefore(attemptedAt)
        || reason == null
        || reason.isBlank()
        || reason.trim().length() > MAX_RECONCILIATION_REASON_LENGTH) {
      throw new IllegalArgumentException(
          "Connect-token reconciliation attempt is outside its bounds");
    }
    Instant persistedAttemptAt = attemptedAt.truncatedTo(ChronoUnit.MICROS);
    Instant persistedNextAttemptAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
    if (persistedNextAttemptAt.isBefore(persistedAttemptAt)) {
      throw new IllegalArgumentException(
          "Connect-token reconciliation times are not ordered at database precision");
    }

    Record changed =
        dsl.fetchOne(
            "UPDATE "
                + OPERATION_TABLE
                + " SET reconciliation_attempt_count = reconciliation_attempt_count + 1, "
                + "last_reconciliation_attempt_at = CAST(? AS TIMESTAMPTZ), "
                + "last_reconciliation_attempt_reason = ?, "
                + "next_reconciliation_attempt_at = CAST(? AS TIMESTAMPTZ) "
                + "WHERE account_id = ? AND tenant_id = ? AND connect_scope_hash = ? "
                + "AND request_id = ? AND request_digest_version = ? AND request_digest = ? "
                + "AND status IN ('PENDING', 'ABORTED') "
                + "AND reconciliation_attempt_count = ? "
                + "AND reconciliation_attempt_count < ? RETURNING operation_id",
            persistedAttemptAt.atOffset(ZoneOffset.UTC).toString(),
            reason.trim(),
            persistedNextAttemptAt.atOffset(ZoneOffset.UTC).toString(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest,
            expectedAttemptCount,
            maxAttempts);
    if (changed == null) {
      throw new IllegalStateException(
          "Connect-token reconciliation operation changed or reached its caller attempt cap");
    }
    AccountConnectTokenIssuanceOperation operation =
        readOperation(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Connect-token reconciliation attempt has no operation readback"));
    if (!changed.get("operation_id", UUID.class).equals(operation.operationId())
        || operation.reconciliationAttemptCount() != expectedAttemptCount + 1
        || !persistedAttemptAt.equals(operation.lastReconciliationAttemptAt())
        || !reason.trim().equals(operation.lastReconciliationAttemptReason())
        || !persistedNextAttemptAt.equals(operation.nextReconciliationAttemptAt())) {
      throw new IllegalStateException(
          "Connect-token reconciliation attempt readback does not match the requested evidence");
    }
    return operation;
  }

  /**
   * Stores one terminal deterministic result with its authenticated ciphertext in the caller's
   * transaction. The operation must still be PENDING and the supplied AEAD binding must name that
   * exact operation and caller identity. No method in this repository creates or recovers a JWT.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountConnectTokenResponseEnvelope completeWithEnvelope(
      ClaimResult claim,
      byte[] requestDigest,
      Lifecycle terminalLifecycle,
      String outcomeCode,
      String tokenIdentity,
      byte[] tokenHash,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    Objects.requireNonNull(claim, "claim");
    AccountConnectTokenIssuanceIdentity identity = requireClaimIdentity(claim);
    byte[] digest = requireDigest(requestDigest, "request digest");
    validateTerminalResult(terminalLifecycle, outcomeCode, tokenIdentity, tokenHash);
    Objects.requireNonNull(binding, "binding");
    validateConnectTokenEnvelope(envelope);

    AccountConnectTokenIssuanceOperation operation = requireClaimedOperation(claim, digest);
    validateBinding(operation, identity, digest, binding);
    requireEvidenceCompatible(
        operation,
        tokenIdentity,
        copyNullable(tokenHash),
        binding.contextEvidenceDigest(),
        binding.authorityTupleDigest(),
        binding.issuanceFenceDigest(),
        binding.postconditionDigest());
    if (terminalLifecycle == Lifecycle.FAILED
        && (operation.tokenIdentity() != null || operation.tokenHash() != null)) {
      throw new EvidenceMismatchException(
          "An operation with token identity evidence cannot become a deterministic failure");
    }
    if (operation.lifecycle() != Lifecycle.PENDING) {
      throw new IllegalStateException(
          "Connect-token issuance operation is not pending and cannot be replaced");
    }

    Record changed =
        dsl.fetchOne(
            "UPDATE "
                + OPERATION_TABLE
                + " SET status = ?, outcome_code = ?, token_identity = ?, token_hash = ?, "
                + "context_evidence_digest = ?, authority_tuple_digest = ?, "
                + "issuance_fence_digest = ?, postcondition_digest = ? "
                + "WHERE operation_id = ? AND account_id = ? AND tenant_id = ? "
                + "AND connect_scope_hash = ? AND request_id = ? "
                + "AND request_digest_version = ? AND request_digest = ? AND status = 'PENDING' "
                + "RETURNING operation_id",
            terminalLifecycle.name(),
            outcomeCode,
            tokenIdentity,
            copyNullable(tokenHash),
            binding.contextEvidenceDigest(),
            binding.authorityTupleDigest(),
            binding.issuanceFenceDigest(),
            binding.postconditionDigest(),
            operation.operationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest);
    if (changed == null
        || !operation.operationId().equals(changed.get("operation_id", UUID.class))) {
      throw new IllegalStateException("Connect-token issuance operation changed before completion");
    }

    return insertEnvelopeAndReadBack(operation, identity, digest, binding, envelope);
  }

  /**
   * Completes an ABORTED operation only after the caller has reconciled the ambiguous external
   * result and revalidated the applicable authority. The expected reconciliation-attempt count is a
   * compare-and-set fence: only the exact still-aborted receipt observed by that reconciliation may
   * become terminal. This method never claims or replaces the first writer's request identity.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountConnectTokenResponseEnvelope resolveAbortedWithEnvelope(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      int expectedReconciliationAttemptCount,
      Lifecycle terminalLifecycle,
      String outcomeCode,
      String tokenIdentity,
      byte[] tokenHash,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    if (expectedReconciliationAttemptCount < 1) {
      throw new IllegalArgumentException(
          "Aborted connect-token resolution requires a recorded reconciliation attempt");
    }
    byte[] checkedTokenHash = copyNullable(tokenHash);
    validateTerminalResult(terminalLifecycle, outcomeCode, tokenIdentity, checkedTokenHash);
    Objects.requireNonNull(binding, "binding");
    validateConnectTokenEnvelope(envelope);

    AccountConnectTokenIssuanceOperation operation =
        readOperation(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Connect-token issuance operation is missing during reconciliation"));
    requireDigestMatch(operation, digest);
    if (operation.lifecycle() != Lifecycle.ABORTED
        || operation.reconciliationAttemptCount() != expectedReconciliationAttemptCount) {
      throw new IllegalStateException(
          "Aborted connect-token operation changed before reconciliation resolution");
    }
    validateBinding(operation, identity, digest, binding);
    requireEvidenceCompatible(
        operation,
        tokenIdentity,
        checkedTokenHash,
        binding.contextEvidenceDigest(),
        binding.authorityTupleDigest(),
        binding.issuanceFenceDigest(),
        binding.postconditionDigest());
    if (terminalLifecycle == Lifecycle.FAILED
        && (operation.tokenIdentity() != null || operation.tokenHash() != null)) {
      throw new EvidenceMismatchException(
          "An operation with token identity evidence cannot become a deterministic failure");
    }

    Record changed =
        dsl.fetchOne(
            "UPDATE "
                + OPERATION_TABLE
                + " SET status = ?, outcome_code = ?, token_identity = ?, token_hash = ?, "
                + "context_evidence_digest = ?, authority_tuple_digest = ?, "
                + "issuance_fence_digest = ?, postcondition_digest = ? "
                + "WHERE operation_id = ? AND account_id = ? AND tenant_id = ? "
                + "AND connect_scope_hash = ? AND request_id = ? "
                + "AND request_digest_version = ? AND request_digest = ? "
                + "AND status = 'ABORTED' AND reconciliation_attempt_count = ? "
                + "RETURNING operation_id",
            terminalLifecycle.name(),
            outcomeCode,
            tokenIdentity,
            checkedTokenHash,
            binding.contextEvidenceDigest(),
            binding.authorityTupleDigest(),
            binding.issuanceFenceDigest(),
            binding.postconditionDigest(),
            operation.operationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest,
            expectedReconciliationAttemptCount);
    if (changed == null
        || !operation.operationId().equals(changed.get("operation_id", UUID.class))) {
      throw new IllegalStateException(
          "Aborted connect-token operation changed before reconciliation resolution");
    }

    AccountConnectTokenIssuanceOperation resolved =
        readOperation(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Resolved connect-token operation has no durable readback"));
    if (!resolved.operationId().equals(operation.operationId())
        || !MessageDigest.isEqual(resolved.requestDigest(), digest)
        || resolved.lifecycle() != terminalLifecycle
        || !Objects.equals(resolved.outcomeCode(), outcomeCode)
        || !Objects.equals(resolved.tokenIdentity(), tokenIdentity)
        || !sameNullableDigest(resolved.tokenHash(), checkedTokenHash)
        || !sameNullableDigest(resolved.contextEvidenceDigest(), binding.contextEvidenceDigest())
        || !sameNullableDigest(resolved.authorityTupleDigest(), binding.authorityTupleDigest())
        || !sameNullableDigest(resolved.issuanceFenceDigest(), binding.issuanceFenceDigest())
        || !sameNullableDigest(resolved.postconditionDigest(), binding.postconditionDigest())
        || resolved.reconciliationAttemptCount() != expectedReconciliationAttemptCount) {
      throw new IllegalStateException(
          "Resolved connect-token operation readback differs from reconciled evidence");
    }
    return insertEnvelopeAndReadBack(resolved, identity, digest, binding, envelope);
  }

  private AccountConnectTokenResponseEnvelope insertEnvelopeAndReadBack(
      AccountConnectTokenIssuanceOperation operation,
      AccountConnectTokenIssuanceIdentity identity,
      byte[] digest,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + ENVELOPE_TABLE
                + " (operation_id, operation_kind, account_id, tenant_id, connect_scope_hash, "
                + "request_id, request_digest_version, request_digest, format_version, key_id, "
                + "purpose, nonce, ciphertext, context_evidence_digest, "
                + "authority_tuple_digest, issuance_fence_digest, postcondition_digest) "
                + "VALUES (?, 'CONNECT_TOKEN_ISSUANCE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            operation.operationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest,
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose().name(),
            envelope.nonce(),
            envelope.ciphertext(),
            binding.contextEvidenceDigest(),
            binding.authorityTupleDigest(),
            binding.issuanceFenceDigest(),
            binding.postconditionDigest());
    if (inserted != 1) {
      throw new IllegalStateException(
          "Connect-token response envelope was not stored exactly once");
    }

    AccountConnectTokenResponseEnvelope stored =
        readResponseEnvelope(identity, digest, binding)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Connect-token response envelope has no exact durable readback"));
    if (!stored.operationId().equals(operation.operationId())
        || !stored.binding().equals(binding)
        || !stored.envelope().equals(envelope)) {
      throw new IllegalStateException(
          "Connect-token response envelope readback differs from the requested envelope");
    }
    return stored;
  }

  /**
   * Reads the exact terminal operation and its only response envelope. A pending or unresolved
   * aborted operation returns empty only when it has no response envelope; an absent envelope for a
   * terminal result or an envelope attached to nonterminal state is ambiguous and fails closed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountConnectTokenResponseEnvelope> readResponseEnvelope(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      AccountEnvelopeBinding binding) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    Objects.requireNonNull(binding, "binding");
    AccountConnectTokenIssuanceOperation operation =
        readOperation(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Connect-token issuance operation is missing during envelope readback"));
    requireDigestMatch(operation, digest);
    validateBinding(operation, identity, digest, binding);

    Record row =
        dsl.fetchOne(
            "SELECT operation_id, operation_kind, account_id, tenant_id, connect_scope_hash, "
                + "request_id, request_digest_version, request_digest, format_version, key_id, "
                + "purpose, nonce, ciphertext, context_evidence_digest, "
                + "authority_tuple_digest, issuance_fence_digest, postcondition_digest "
                + "FROM "
                + ENVELOPE_TABLE
                + " WHERE operation_id = ?",
            operation.operationId());
    boolean terminal =
        operation.lifecycle() == Lifecycle.COMMITTED || operation.lifecycle() == Lifecycle.FAILED;
    if (!terminal) {
      if (row != null) {
        throw new IllegalStateException(
            "Nonterminal connect-token operation unexpectedly owns a response envelope");
      }
      return Optional.empty();
    }
    if (row == null) {
      throw new IllegalStateException(
          "Terminal connect-token issuance result has no response envelope");
    }

    requireEnvelopeIdentity(row, operation, identity, digest, binding);
    AccountEnvelopePurpose purpose;
    try {
      purpose = AccountEnvelopePurpose.valueOf(requiredText(row, "purpose"));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored connect-token response purpose is invalid");
    }
    if (purpose != AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE) {
      throw new IllegalStateException("Stored response envelope has the wrong purpose");
    }
    AccountEncryptedEnvelope encrypted =
        new AccountEncryptedEnvelope(
            requiredInt(row, "format_version"),
            requiredText(row, "key_id"),
            purpose,
            requiredBytes(row, "nonce"),
            requiredBytes(row, "ciphertext"));
    return Optional.of(
        new AccountConnectTokenResponseEnvelope(operation.operationId(), binding, encrypted));
  }

  private Optional<AccountConnectTokenIssuanceOperation> readOperation(
      AccountConnectTokenIssuanceIdentity identity) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, account_id, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, status, outcome_code, token_identity, "
                + "token_hash, context_evidence_digest, authority_tuple_digest, "
                + "issuance_fence_digest, postcondition_digest, reconciliation_attempt_count, "
                + "last_reconciliation_attempt_at, last_reconciliation_attempt_reason, "
                + "next_reconciliation_attempt_at, created_at, updated_at FROM "
                + OPERATION_TABLE
                + " WHERE account_id = ? AND tenant_id = ? AND connect_scope_hash = ? "
                + "AND request_id = ?",
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId());
    return row == null ? Optional.empty() : Optional.of(toOperation(row, identity));
  }

  private static AccountConnectTokenIssuanceOperation toOperation(
      Record row, AccountConnectTokenIssuanceIdentity identity) {
    long accountId = requiredLong(row, "account_id");
    long tenantId = requiredLong(row, "tenant_id");
    String scopeHash = requiredText(row, "connect_scope_hash");
    String requestId = requiredText(row, "request_id");
    if (accountId != identity.accountId()
        || tenantId != identity.tenantId()
        || !scopeHash.equals(identity.connectScopeHash())
        || !requestId.equals(identity.requestId())) {
      throw new IllegalStateException("Connect-token issuance readback identity does not match");
    }
    return new AccountConnectTokenIssuanceOperation(
        requiredUuid(row, "operation_id"),
        accountId,
        tenantId,
        scopeHash,
        requestId,
        requiredInt(row, "request_digest_version"),
        requiredBytes(row, "request_digest"),
        Lifecycle.fromDatabase(requiredText(row, "status")),
        row.get("outcome_code", String.class),
        row.get("token_identity", String.class),
        row.get("token_hash", byte[].class),
        row.get("context_evidence_digest", byte[].class),
        row.get("authority_tuple_digest", byte[].class),
        row.get("issuance_fence_digest", byte[].class),
        row.get("postcondition_digest", byte[].class),
        requiredInt(row, "reconciliation_attempt_count"),
        toInstant(row.get("last_reconciliation_attempt_at", OffsetDateTime.class)),
        row.get("last_reconciliation_attempt_reason", String.class),
        toInstant(row.get("next_reconciliation_attempt_at", OffsetDateTime.class)),
        toInstant(row.get("created_at", OffsetDateTime.class)),
        toInstant(row.get("updated_at", OffsetDateTime.class)));
  }

  private static void requireEnvelopeIdentity(
      Record row,
      AccountConnectTokenIssuanceOperation operation,
      AccountConnectTokenIssuanceIdentity identity,
      byte[] digest,
      AccountEnvelopeBinding binding) {
    if (!operation.operationId().equals(requiredUuid(row, "operation_id"))
        || !"CONNECT_TOKEN_ISSUANCE".equals(requiredText(row, "operation_kind"))
        || requiredLong(row, "account_id") != identity.accountId()
        || requiredLong(row, "tenant_id") != identity.tenantId()
        || !identity.connectScopeHash().equals(requiredText(row, "connect_scope_hash"))
        || !identity.requestId().equals(requiredText(row, "request_id"))
        || requiredInt(row, "request_digest_version") != REQUEST_DIGEST_VERSION
        || !MessageDigest.isEqual(digest, requiredBytes(row, "request_digest"))
        || !MessageDigest.isEqual(
            operation.contextEvidenceDigest(), requiredBytes(row, "context_evidence_digest"))
        || !MessageDigest.isEqual(
            operation.authorityTupleDigest(), requiredBytes(row, "authority_tuple_digest"))
        || !MessageDigest.isEqual(
            operation.issuanceFenceDigest(), requiredBytes(row, "issuance_fence_digest"))
        || !MessageDigest.isEqual(
            operation.postconditionDigest(), requiredBytes(row, "postcondition_digest"))) {
      throw new IllegalStateException(
          "Connect-token response envelope identity or evidence readback does not match");
    }
    validateBinding(operation, identity, digest, binding);
  }

  private static void validateBinding(
      AccountConnectTokenIssuanceOperation operation,
      AccountConnectTokenIssuanceIdentity identity,
      byte[] digest,
      AccountEnvelopeBinding binding) {
    if (binding.operationKind() != AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE
        || !operation.operationId().toString().equals(binding.operationId())
        || !identity.requestId().equals(binding.requestId())
        || !Long.toString(identity.accountId()).equals(binding.accountId())
        || !Long.toString(identity.tenantId()).equals(binding.tenantId())
        || !identity.connectScopeId().equals(binding.connectScopeId())
        || !MessageDigest.isEqual(digest, binding.requestDigest())) {
      throw new EvidenceMismatchException(
          "Connect-token response binding does not name the exact operation identity and digest");
    }
    if (operation.lifecycle() == Lifecycle.COMMITTED || operation.lifecycle() == Lifecycle.FAILED) {
      requireOperationEvidence(operation, binding);
    }
  }

  private static void requireOperationEvidence(
      AccountConnectTokenIssuanceOperation operation, AccountEnvelopeBinding binding) {
    if (!MessageDigest.isEqual(operation.contextEvidenceDigest(), binding.contextEvidenceDigest())
        || !MessageDigest.isEqual(operation.authorityTupleDigest(), binding.authorityTupleDigest())
        || !MessageDigest.isEqual(operation.issuanceFenceDigest(), binding.issuanceFenceDigest())
        || !MessageDigest.isEqual(operation.postconditionDigest(), binding.postconditionDigest())) {
      throw new EvidenceMismatchException(
          "Connect-token response binding differs from committed operation evidence");
    }
  }

  private static void validateTerminalResult(
      Lifecycle lifecycle, String outcomeCode, String tokenIdentity, byte[] tokenHash) {
    if (lifecycle == Lifecycle.COMMITTED) {
      if (!"SUCCESS".equals(outcomeCode)
          || tokenIdentity == null
          || tokenIdentity.isBlank()
          || tokenIdentity.length() > MAX_TOKEN_IDENTITY_LENGTH
          || tokenIdentity.indexOf('\0') >= 0
          || tokenHash == null
          || tokenHash.length != DIGEST_LENGTH_BYTES) {
        throw new IllegalArgumentException("Committed connect-token result evidence is invalid");
      }
      return;
    }
    if (lifecycle != Lifecycle.FAILED
        || outcomeCode == null
        || outcomeCode.isBlank()
        || outcomeCode.length() > MAX_OUTCOME_CODE_LENGTH
        || outcomeCode.indexOf('\0') >= 0
        || "SUCCESS".equals(outcomeCode)
        || tokenIdentity != null
        || tokenHash != null) {
      throw new IllegalArgumentException("Deterministic connect-token failure evidence is invalid");
    }
  }

  private AccountConnectTokenIssuanceIdentity requireClaimIdentity(ClaimResult claim) {
    Objects.requireNonNull(claim, "claim");
    if (claim.disposition() != ClaimDisposition.CLAIMED) {
      throw new IllegalStateException(
          "Only the first connect-token operation claimant may record evidence or an outcome");
    }
    return claim.identity();
  }

  private AccountConnectTokenIssuanceOperation requireClaimedOperation(
      ClaimResult claim, byte[] digest) {
    AccountConnectTokenIssuanceIdentity identity = requireClaimIdentity(claim);
    AccountConnectTokenIssuanceOperation operation =
        readOperation(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Connect-token issuance operation is missing during owner update"));
    if (!claim.operation().operationId().equals(operation.operationId())) {
      throw new IllegalStateException("Connect-token claim no longer owns the durable operation");
    }
    requireDigestMatch(operation, digest);
    return operation;
  }

  private static void requireEvidenceCompatible(
      AccountConnectTokenIssuanceOperation operation,
      String tokenIdentity,
      byte[] tokenHash,
      byte[] contextEvidenceDigest,
      byte[] authorityTupleDigest,
      byte[] issuanceFenceDigest,
      byte[] postconditionDigest) {
    if ((operation.tokenIdentity() != null
            && tokenIdentity != null
            && !operation.tokenIdentity().equals(tokenIdentity))
        || differsWhenProvided(operation.tokenHash(), tokenHash)
        || differsWhenProvided(operation.contextEvidenceDigest(), contextEvidenceDigest)
        || differsWhenProvided(operation.authorityTupleDigest(), authorityTupleDigest)
        || differsWhenProvided(operation.issuanceFenceDigest(), issuanceFenceDigest)
        || differsWhenProvided(operation.postconditionDigest(), postconditionDigest)) {
      throw new EvidenceMismatchException(
          "Connect-token issuance evidence is already bound to different values");
    }
  }

  private static boolean differsWhenProvided(byte[] stored, byte[] candidate) {
    return stored != null && candidate != null && !MessageDigest.isEqual(stored, candidate);
  }

  private static boolean sameNullableDigest(byte[] left, byte[] right) {
    return left == null ? right == null : right != null && MessageDigest.isEqual(left, right);
  }

  private static void validateConnectTokenEnvelope(AccountEncryptedEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.purpose() != AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE) {
      throw new EvidenceMismatchException(
          "Connect-token issuance cannot store an envelope for another purpose");
    }
  }

  private static void requireDigestMatch(
      AccountConnectTokenIssuanceOperation operation, byte[] expectedDigest) {
    if (!MessageDigest.isEqual(operation.requestDigest(), expectedDigest)) {
      throw new IdempotencyConflictException(
          "Connect-token request identity was reused with a different digest");
    }
  }

  private static void validateIdentity(AccountConnectTokenIssuanceIdentity identity) {
    Objects.requireNonNull(identity, "identity");
  }

  private static byte[] requireDigest(byte[] value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return Arrays.copyOf(value, value.length);
  }

  private static byte[] optionalDigest(byte[] value, String fieldName) {
    return value == null ? null : requireDigest(value, fieldName);
  }

  private static byte[] copyNullable(byte[] value) {
    return value == null ? null : value.clone();
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : JooqPersistenceSupport.toInstant(value);
  }

  private static long requiredLong(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) {
      throw new IllegalStateException("Stored connect-token issuance field is missing: " + field);
    }
    return value;
  }

  private static int requiredInt(Record row, String field) {
    Integer value = row.get(field, Integer.class);
    if (value == null) {
      throw new IllegalStateException("Stored connect-token issuance field is missing: " + field);
    }
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) {
      throw new IllegalStateException("Stored connect-token issuance field is missing: " + field);
    }
    return value;
  }

  private static String requiredText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Stored connect-token issuance field is missing: " + field);
    }
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Stored connect-token issuance field is missing: " + field);
    }
    return value;
  }

  public enum ClaimDisposition {
    CLAIMED,
    REPLAYED
  }

  public static final class ClaimResult {
    private final ClaimDisposition disposition;
    private final AccountConnectTokenIssuanceIdentity identity;
    private final AccountConnectTokenIssuanceOperation operation;

    private ClaimResult(
        ClaimDisposition disposition,
        AccountConnectTokenIssuanceIdentity identity,
        AccountConnectTokenIssuanceOperation operation) {
      this.disposition = Objects.requireNonNull(disposition, "disposition");
      this.identity = Objects.requireNonNull(identity, "identity");
      this.operation = Objects.requireNonNull(operation, "operation");
    }

    public ClaimDisposition disposition() {
      return disposition;
    }

    public AccountConnectTokenIssuanceIdentity identity() {
      return identity;
    }

    public AccountConnectTokenIssuanceOperation operation() {
      return operation;
    }
  }

  public static class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }

  public static class EvidenceMismatchException extends RuntimeException {
    public EvidenceMismatchException(String message) {
      super(message);
    }
  }
}
