package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeOperation.Lifecycle;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-owned first-writer storage and exact readback for bare first-party LOGIN exchange. Claim,
 * pending evidence, terminal success, and its exact envelope must share one Account transaction;
 * V37 permits {@code PENDING} only as an in-transaction intermediate and rejects a still-pending
 * exchange at transaction commit.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountBareLoginExchangeRepository {
  private static final String OPERATION_TABLE = "account_bare_login_exchange_operations";
  private static final String SOURCE_OPERATION_TABLE = "account_connect_token_issuance_operations";
  private static final String ENVELOPE_TABLE = "account_bare_login_response_envelopes";
  private static final int REQUEST_DIGEST_VERSION = 1;
  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TOKEN_IDENTITY_LENGTH = 128;

  private final DSLContext dsl;

  public AccountBareLoginExchangeRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Claims one source connect-operation identity without replacing a prior writer's request
   * identity or digest. A source connect operation must already be a committed V35 issuance; a
   * pending or absent source is not Account authentication and cannot start an exchange. The caller
   * must keep this claim in the same Account transaction as pending evidence and terminal envelope
   * completion; committing the claim alone is rejected.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ClaimResult claim(AccountBareLoginExchangeIdentity identity, byte[] requestDigest) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    requireCommittedSource(identity);

    Optional<AccountBareLoginExchangeOperation> existing = readOperationBySource(identity);
    if (existing.isEmpty()) {
      existing = readOperationByRequest(identity);
    }
    if (existing.isPresent()) {
      AccountBareLoginExchangeOperation operation = existing.orElseThrow();
      assertIdentityMatches(operation, identity);
      requireDigestMatch(operation, digest);
      return new ClaimResult(ClaimDisposition.REPLAYED, identity, operation);
    }

    UUID proposedOperationId = UUID.randomUUID();
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATION_TABLE
                + " (operation_id, source_connect_operation_id, account_id, tenant_id, "
                + "connect_scope_hash, request_id, request_digest_version, request_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING') "
                + "ON CONFLICT DO NOTHING",
            proposedOperationId,
            identity.sourceConnectOperationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest);
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Bare LOGIN exchange claim result was ambiguous");
    }

    Optional<AccountBareLoginExchangeOperation> winner = readOperationBySource(identity);
    if (winner.isEmpty()) {
      winner = readOperationByRequest(identity);
    }
    AccountBareLoginExchangeOperation operation =
        winner.orElseThrow(
            () ->
                new IllegalStateException(
                    "Bare LOGIN exchange claim has no exact durable readback"));
    assertIdentityMatches(operation, identity);
    requireDigestMatch(operation, digest);
    if (inserted == 1 && !proposedOperationId.equals(operation.operationId())) {
      throw new IllegalStateException("Bare LOGIN exchange claim returned another operation");
    }
    return new ClaimResult(
        inserted == 1 ? ClaimDisposition.CLAIMED : ClaimDisposition.REPLAYED, identity, operation);
  }

  /** Reads one exact source/request identity and rejects a changed request digest. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountBareLoginExchangeOperation> find(
      AccountBareLoginExchangeIdentity identity, byte[] requestDigest) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    requireCommittedSource(identity);
    Optional<AccountBareLoginExchangeOperation> operation = readOperationBySource(identity);
    if (operation.isEmpty()) {
      operation = readOperationByRequest(identity);
    }
    if (operation.isPresent()) {
      assertIdentityMatches(operation.orElseThrow(), identity);
      requireDigestMatch(operation.orElseThrow(), digest);
    }
    return operation;
  }

  /**
   * Adds write-once source/token/authority evidence to a still-pending exchange. It never marks a
   * transient or ambiguous result terminal; the claim, this evidence binding, and the later
   * terminal call must share one transaction, which must store the exact purpose-bound envelope
   * before commit.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountBareLoginExchangeOperation recordPendingEvidence(
      ClaimResult claim,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash,
      byte[] contextEvidenceDigest,
      byte[] authorityTupleDigest,
      byte[] issuanceFenceDigest,
      byte[] postconditionDigest) {
    AccountBareLoginExchangeIdentity identity = requireClaimIdentity(claim);
    byte[] digest = requireDigest(requestDigest, "request digest");
    byte[] checkedTokenHash = optionalDigest(tokenHash, "token hash");
    byte[] checkedContextDigest = optionalDigest(contextEvidenceDigest, "context digest");
    byte[] checkedAuthorityDigest = optionalDigest(authorityTupleDigest, "authority tuple digest");
    byte[] checkedFenceDigest = optionalDigest(issuanceFenceDigest, "issuance-fence digest");
    byte[] checkedPostconditionDigest = optionalDigest(postconditionDigest, "postcondition digest");
    validateTokenEvidence(tokenIdentity, checkedTokenHash);
    if (tokenIdentity == null
        && checkedContextDigest == null
        && checkedAuthorityDigest == null
        && checkedFenceDigest == null
        && checkedPostconditionDigest == null) {
      throw new IllegalArgumentException(
          "At least one pending bare LOGIN exchange evidence field is required");
    }

    AccountBareLoginExchangeOperation operation = requireClaimedOperation(claim, digest);
    if (operation.lifecycle() != Lifecycle.PENDING) {
      throw new IllegalStateException("Only a pending bare LOGIN exchange can add evidence");
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
                + " SET token_identity = COALESCE(?, token_identity), "
                + "token_hash = COALESCE(?, token_hash), "
                + "context_evidence_digest = COALESCE(?, context_evidence_digest), "
                + "authority_tuple_digest = COALESCE(?, authority_tuple_digest), "
                + "issuance_fence_digest = COALESCE(?, issuance_fence_digest), "
                + "postcondition_digest = COALESCE(?, postcondition_digest) "
                + "WHERE operation_id = ? AND source_connect_operation_id = ? "
                + "AND account_id = ? AND tenant_id = ? AND connect_scope_hash = ? "
                + "AND request_id = ? AND request_digest_version = ? AND request_digest = ? "
                + "AND status = 'PENDING' "
                + "AND (?::VARCHAR IS NULL OR token_identity IS NULL OR token_identity = ?) "
                + "AND (?::BYTEA IS NULL OR token_hash IS NULL OR token_hash = ?) "
                + "AND (?::BYTEA IS NULL OR context_evidence_digest IS NULL "
                + "OR context_evidence_digest = ?) "
                + "AND (?::BYTEA IS NULL OR authority_tuple_digest IS NULL "
                + "OR authority_tuple_digest = ?) "
                + "AND (?::BYTEA IS NULL OR issuance_fence_digest IS NULL "
                + "OR issuance_fence_digest = ?) "
                + "AND (?::BYTEA IS NULL OR postcondition_digest IS NULL "
                + "OR postcondition_digest = ?) RETURNING operation_id",
            tokenIdentity,
            checkedTokenHash,
            checkedContextDigest,
            checkedAuthorityDigest,
            checkedFenceDigest,
            checkedPostconditionDigest,
            operation.operationId(),
            identity.sourceConnectOperationId(),
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
          "Pending bare LOGIN exchange already contains different evidence");
    }
    AccountBareLoginExchangeOperation updated =
        readOperationBySource(identity)
            .filter(read -> read.operationId().equals(operation.operationId()))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Pending bare LOGIN exchange evidence has no exact operation readback"));
    return updated;
  }

  /**
   * Stores one terminal successful result and its authenticated ciphertext in the caller's
   * transaction. The claim and any pending evidence must have occurred in this same transaction;
   * this repository never creates or decrypts the delegation JWT.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountBareLoginResponseEnvelope completeWithEnvelope(
      ClaimResult claim,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    AccountBareLoginExchangeIdentity identity = requireClaimIdentity(claim);
    byte[] digest = requireDigest(requestDigest, "request digest");
    validateTerminalResult(tokenIdentity, tokenHash);
    Objects.requireNonNull(binding, "binding");
    validateBareLoginEnvelope(envelope);

    AccountBareLoginExchangeOperation operation = requireClaimedOperation(claim, digest);
    if (operation.lifecycle() != Lifecycle.PENDING) {
      throw new IllegalStateException(
          "Bare LOGIN exchange operation is not pending and cannot be replaced");
    }
    validateBinding(operation, identity, digest, binding);
    requireEvidenceCompatible(
        operation,
        tokenIdentity,
        copyNullable(tokenHash),
        binding.contextEvidenceDigest(),
        binding.authorityTupleDigest(),
        binding.issuanceFenceDigest(),
        binding.postconditionDigest());

    Record changed =
        dsl.fetchOne(
            "UPDATE "
                + OPERATION_TABLE
                + " SET status = 'COMMITTED', outcome_code = 'SUCCESS', token_identity = ?, "
                + "token_hash = ?, context_evidence_digest = ?, authority_tuple_digest = ?, "
                + "issuance_fence_digest = ?, postcondition_digest = ? "
                + "WHERE operation_id = ? AND source_connect_operation_id = ? "
                + "AND account_id = ? AND tenant_id = ? AND connect_scope_hash = ? "
                + "AND request_id = ? AND request_digest_version = ? AND request_digest = ? "
                + "AND status = 'PENDING' "
                + "AND token_identity IS NOT DISTINCT FROM CAST(? AS VARCHAR) "
                + "AND token_hash IS NOT DISTINCT FROM CAST(? AS BYTEA) "
                + "AND context_evidence_digest IS NOT DISTINCT FROM CAST(? AS BYTEA) "
                + "AND authority_tuple_digest IS NOT DISTINCT FROM CAST(? AS BYTEA) "
                + "AND issuance_fence_digest IS NOT DISTINCT FROM CAST(? AS BYTEA) "
                + "AND postcondition_digest IS NOT DISTINCT FROM CAST(? AS BYTEA) "
                + "RETURNING operation_id",
            tokenIdentity,
            copyNullable(tokenHash),
            binding.contextEvidenceDigest(),
            binding.authorityTupleDigest(),
            binding.issuanceFenceDigest(),
            binding.postconditionDigest(),
            operation.operationId(),
            identity.sourceConnectOperationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId(),
            REQUEST_DIGEST_VERSION,
            digest,
            operation.tokenIdentity(),
            operation.tokenHash(),
            operation.contextEvidenceDigest(),
            operation.authorityTupleDigest(),
            operation.issuanceFenceDigest(),
            operation.postconditionDigest());
    if (changed == null
        || !operation.operationId().equals(changed.get("operation_id", UUID.class))) {
      throw new EvidenceMismatchException("Bare LOGIN exchange evidence changed before completion");
    }

    AccountBareLoginExchangeOperation committed =
        readOperationBySource(identity)
            .filter(read -> read.operationId().equals(operation.operationId()))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed bare LOGIN exchange has no exact operation readback"));
    if (committed.lifecycle() != Lifecycle.COMMITTED
        || !"SUCCESS".equals(committed.outcomeCode())
        || !Objects.equals(committed.tokenIdentity(), tokenIdentity)
        || !sameDigest(committed.tokenHash(), tokenHash)
        || !sameDigest(committed.contextEvidenceDigest(), binding.contextEvidenceDigest())
        || !sameDigest(committed.authorityTupleDigest(), binding.authorityTupleDigest())
        || !sameDigest(committed.issuanceFenceDigest(), binding.issuanceFenceDigest())
        || !sameDigest(committed.postconditionDigest(), binding.postconditionDigest())) {
      throw new IllegalStateException(
          "Committed bare LOGIN exchange readback differs from requested evidence");
    }
    return insertEnvelopeAndReadBack(committed, identity, digest, binding, envelope);
  }

  /**
   * Reads the exact terminal operation and its only response envelope. A pending operation without
   * an envelope remains pending; a terminal operation without its exact envelope fails closed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountBareLoginResponseEnvelope> readResponseEnvelope(
      AccountBareLoginExchangeIdentity identity,
      byte[] requestDigest,
      AccountEnvelopeBinding binding) {
    validateIdentity(identity);
    byte[] digest = requireDigest(requestDigest, "request digest");
    Objects.requireNonNull(binding, "binding");
    requireCommittedSource(identity);
    AccountBareLoginExchangeOperation operation =
        readOperationBySource(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Bare LOGIN exchange operation is missing during envelope readback"));
    assertIdentityMatches(operation, identity);
    requireDigestMatch(operation, digest);
    validateBinding(operation, identity, digest, binding);

    Record row =
        dsl.fetchOne(
            "SELECT operation_id, operation_kind, source_connect_operation_id, account_id, "
                + "tenant_id, connect_scope_hash, request_id, request_digest_version, "
                + "request_digest, format_version, key_id, purpose, nonce, ciphertext, "
                + "context_evidence_digest, authority_tuple_digest, issuance_fence_digest, "
                + "postcondition_digest FROM "
                + ENVELOPE_TABLE
                + " WHERE operation_id = ?",
            operation.operationId());
    if (operation.lifecycle() == Lifecycle.PENDING) {
      if (row != null) {
        throw new IllegalStateException(
            "Pending bare LOGIN exchange unexpectedly owns a response envelope");
      }
      return Optional.empty();
    }
    if (row == null) {
      throw new IllegalStateException(
          "Committed bare LOGIN exchange result has no response envelope");
    }

    requireEnvelopeIdentity(row, operation, identity, digest, binding);
    AccountEnvelopePurpose purpose;
    try {
      purpose = AccountEnvelopePurpose.valueOf(requiredText(row, "purpose"));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored bare LOGIN response purpose is invalid");
    }
    if (purpose != AccountEnvelopePurpose.BARE_LOGIN_RESPONSE) {
      throw new IllegalStateException("Stored bare LOGIN response envelope has the wrong purpose");
    }
    AccountEncryptedEnvelope encrypted =
        new AccountEncryptedEnvelope(
            requiredInt(row, "format_version"),
            requiredText(row, "key_id"),
            purpose,
            requiredBytes(row, "nonce"),
            requiredBytes(row, "ciphertext"));
    return Optional.of(
        new AccountBareLoginResponseEnvelope(operation.operationId(), binding, encrypted));
  }

  private AccountBareLoginResponseEnvelope insertEnvelopeAndReadBack(
      AccountBareLoginExchangeOperation operation,
      AccountBareLoginExchangeIdentity identity,
      byte[] digest,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + ENVELOPE_TABLE
                + " (operation_id, operation_kind, source_connect_operation_id, account_id, "
                + "tenant_id, connect_scope_hash, request_id, request_digest_version, "
                + "request_digest, format_version, key_id, purpose, nonce, ciphertext, "
                + "context_evidence_digest, authority_tuple_digest, issuance_fence_digest, "
                + "postcondition_digest) VALUES (?, 'BARE_LOGIN_EXCHANGE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            operation.operationId(),
            identity.sourceConnectOperationId(),
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
      throw new IllegalStateException("Bare LOGIN response envelope was not stored exactly once");
    }

    AccountBareLoginResponseEnvelope stored =
        readResponseEnvelope(identity, digest, binding)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Bare LOGIN response envelope has no exact durable readback"));
    if (!stored.operationId().equals(operation.operationId())
        || !stored.binding().equals(binding)
        || !stored.envelope().equals(envelope)) {
      throw new IllegalStateException(
          "Bare LOGIN response envelope readback differs from requested envelope");
    }
    return stored;
  }

  private Optional<AccountBareLoginExchangeOperation> readOperationBySource(
      AccountBareLoginExchangeIdentity identity) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, source_connect_operation_id, account_id, tenant_id, "
                + "connect_scope_hash, request_id, request_digest_version, request_digest, status, "
                + "outcome_code, token_identity, token_hash, context_evidence_digest, "
                + "authority_tuple_digest, issuance_fence_digest, postcondition_digest, "
                + "created_at, updated_at FROM "
                + OPERATION_TABLE
                + " WHERE source_connect_operation_id = ?",
            identity.sourceConnectOperationId());
    return row == null ? Optional.empty() : Optional.of(toOperation(row));
  }

  private Optional<AccountBareLoginExchangeOperation> readOperationByRequest(
      AccountBareLoginExchangeIdentity identity) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, source_connect_operation_id, account_id, tenant_id, "
                + "connect_scope_hash, request_id, request_digest_version, request_digest, status, "
                + "outcome_code, token_identity, token_hash, context_evidence_digest, "
                + "authority_tuple_digest, issuance_fence_digest, postcondition_digest, "
                + "created_at, updated_at FROM "
                + OPERATION_TABLE
                + " WHERE account_id = ? AND tenant_id = ? AND connect_scope_hash = ? "
                + "AND request_id = ?",
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeHash(),
            identity.requestId());
    return row == null ? Optional.empty() : Optional.of(toOperation(row));
  }

  private void requireCommittedSource(AccountBareLoginExchangeIdentity identity) {
    Record source =
        dsl.fetchOne(
            "SELECT operation_id, account_id, tenant_id, connect_scope_hash, status FROM "
                + SOURCE_OPERATION_TABLE
                + " WHERE operation_id = ?",
            identity.sourceConnectOperationId());
    if (source == null) {
      throw new IllegalStateException(
          "Bare LOGIN exchange source connect operation is missing or untrusted");
    }
    String status = requiredText(source, "status");
    if (!"COMMITTED".equals(status)) {
      throw new IllegalStateException(
          "Bare LOGIN exchange source connect operation is not committed");
    }
    if (requiredLong(source, "account_id") != identity.accountId()
        || !requiredUuid(source, "tenant_id").equals(identity.tenantId())
        || !identity.connectScopeHash().equals(requiredText(source, "connect_scope_hash"))) {
      throw new IdentityConflictException(
          "Bare LOGIN exchange source connect operation identity does not match");
    }
  }

  private static AccountBareLoginExchangeOperation toOperation(Record row) {
    return new AccountBareLoginExchangeOperation(
        requiredUuid(row, "operation_id"),
        requiredUuid(row, "source_connect_operation_id"),
        requiredLong(row, "account_id"),
        requiredUuid(row, "tenant_id"),
        requiredText(row, "connect_scope_hash"),
        requiredText(row, "request_id"),
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
        toInstant(row.get("created_at", OffsetDateTime.class)),
        toInstant(row.get("updated_at", OffsetDateTime.class)));
  }

  private static void requireEnvelopeIdentity(
      Record row,
      AccountBareLoginExchangeOperation operation,
      AccountBareLoginExchangeIdentity identity,
      byte[] digest,
      AccountEnvelopeBinding binding) {
    if (!operation.operationId().equals(requiredUuid(row, "operation_id"))
        || !"BARE_LOGIN_EXCHANGE".equals(requiredText(row, "operation_kind"))
        || !operation
            .sourceConnectOperationId()
            .equals(requiredUuid(row, "source_connect_operation_id"))
        || requiredLong(row, "account_id") != identity.accountId()
        || !requiredUuid(row, "tenant_id").equals(identity.tenantId())
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
          "Bare LOGIN response envelope identity or evidence readback does not match");
    }
    validateBinding(operation, identity, digest, binding);
  }

  private static void validateBinding(
      AccountBareLoginExchangeOperation operation,
      AccountBareLoginExchangeIdentity identity,
      byte[] digest,
      AccountEnvelopeBinding binding) {
    if (binding.operationKind() != AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE
        || !operation.operationId().toString().equals(binding.operationId())
        || !identity.requestId().equals(binding.requestId())
        || !Long.toString(identity.accountId()).equals(binding.accountId())
        || !identity.tenantId().toString().equals(binding.tenantId())
        || !identity.connectScopeId().equals(binding.connectScopeId())
        || !identity
            .sourceConnectOperationId()
            .toString()
            .equals(binding.sourceConnectOperationId())
        || !MessageDigest.isEqual(digest, binding.requestDigest())) {
      throw new EvidenceMismatchException(
          "Bare LOGIN response binding does not name the exact operation identity and digest");
    }
    if (operation.lifecycle() == Lifecycle.COMMITTED) {
      requireOperationEvidence(operation, binding);
    } else {
      requireEvidenceCompatible(
          operation,
          null,
          null,
          binding.contextEvidenceDigest(),
          binding.authorityTupleDigest(),
          binding.issuanceFenceDigest(),
          binding.postconditionDigest());
    }
  }

  private static void requireOperationEvidence(
      AccountBareLoginExchangeOperation operation, AccountEnvelopeBinding binding) {
    if (!sameDigest(operation.contextEvidenceDigest(), binding.contextEvidenceDigest())
        || !sameDigest(operation.authorityTupleDigest(), binding.authorityTupleDigest())
        || !sameDigest(operation.issuanceFenceDigest(), binding.issuanceFenceDigest())
        || !sameDigest(operation.postconditionDigest(), binding.postconditionDigest())) {
      throw new EvidenceMismatchException(
          "Bare LOGIN response binding differs from committed operation evidence");
    }
  }

  private static void validateTerminalResult(String tokenIdentity, byte[] tokenHash) {
    if (tokenIdentity == null
        || tokenIdentity.isBlank()
        || tokenIdentity.length() > MAX_TOKEN_IDENTITY_LENGTH
        || tokenIdentity.indexOf('\0') >= 0
        || tokenHash == null
        || tokenHash.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException("Committed bare LOGIN result evidence is invalid");
    }
  }

  private static void validateTokenEvidence(String tokenIdentity, byte[] tokenHash) {
    if ((tokenIdentity == null) != (tokenHash == null)) {
      throw new IllegalArgumentException("Bare LOGIN token identity and hash evidence must pair");
    }
    if (tokenIdentity != null
        && (tokenIdentity.isBlank()
            || tokenIdentity.length() > MAX_TOKEN_IDENTITY_LENGTH
            || tokenIdentity.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("Bare LOGIN token identity evidence is invalid");
    }
  }

  private AccountBareLoginExchangeIdentity requireClaimIdentity(ClaimResult claim) {
    Objects.requireNonNull(claim, "claim");
    if (claim.disposition() != ClaimDisposition.CLAIMED) {
      throw new IllegalStateException(
          "Only the first bare LOGIN exchange claimant may record evidence or an outcome");
    }
    return claim.identity();
  }

  private AccountBareLoginExchangeOperation requireClaimedOperation(
      ClaimResult claim, byte[] digest) {
    AccountBareLoginExchangeIdentity identity = requireClaimIdentity(claim);
    requireCommittedSource(identity);
    AccountBareLoginExchangeOperation operation =
        readOperationBySource(identity)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Bare LOGIN exchange operation is missing during owner update"));
    if (!claim.operation().operationId().equals(operation.operationId())) {
      throw new IllegalStateException("Bare LOGIN claim no longer owns the durable operation");
    }
    assertIdentityMatches(operation, identity);
    requireDigestMatch(operation, digest);
    return operation;
  }

  private static void assertIdentityMatches(
      AccountBareLoginExchangeOperation operation, AccountBareLoginExchangeIdentity identity) {
    if (!identity.sourceConnectOperationId().equals(operation.sourceConnectOperationId())
        || identity.accountId() != operation.accountId()
        || !identity.tenantId().equals(operation.tenantId())
        || !identity.connectScopeHash().equals(operation.connectScopeHash())
        || !identity.requestId().equals(operation.requestId())) {
      throw new IdentityConflictException(
          "Bare LOGIN exchange identity was reused with different source or binding");
    }
  }

  private static void requireEvidenceCompatible(
      AccountBareLoginExchangeOperation operation,
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
          "Bare LOGIN exchange evidence is already bound to different values");
    }
  }

  private static boolean differsWhenProvided(byte[] stored, byte[] candidate) {
    return stored != null && candidate != null && !MessageDigest.isEqual(stored, candidate);
  }

  private static boolean sameDigest(byte[] left, byte[] right) {
    return left == null ? right == null : right != null && MessageDigest.isEqual(left, right);
  }

  private static void validateBareLoginEnvelope(AccountEncryptedEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.purpose() != AccountEnvelopePurpose.BARE_LOGIN_RESPONSE) {
      throw new EvidenceMismatchException(
          "Bare LOGIN exchange cannot store an envelope for another purpose");
    }
  }

  private static void requireDigestMatch(
      AccountBareLoginExchangeOperation operation, byte[] expectedDigest) {
    if (!MessageDigest.isEqual(operation.requestDigest(), expectedDigest)) {
      throw new IdempotencyConflictException(
          "Bare LOGIN exchange request identity was reused with a different digest");
    }
  }

  private static void validateIdentity(AccountBareLoginExchangeIdentity identity) {
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
      throw new IllegalStateException("Stored bare LOGIN exchange field is missing: " + field);
    }
    return value;
  }

  private static int requiredInt(Record row, String field) {
    Integer value = row.get(field, Integer.class);
    if (value == null) {
      throw new IllegalStateException("Stored bare LOGIN exchange field is missing: " + field);
    }
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) {
      throw new IllegalStateException("Stored bare LOGIN exchange field is missing: " + field);
    }
    return value;
  }

  private static String requiredText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Stored bare LOGIN exchange field is missing: " + field);
    }
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Stored bare LOGIN exchange field is missing: " + field);
    }
    return value;
  }

  public enum ClaimDisposition {
    CLAIMED,
    REPLAYED
  }

  public static class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }

  public static final class IdentityConflictException extends IdempotencyConflictException {
    public IdentityConflictException(String message) {
      super(message);
    }
  }

  public static final class EvidenceMismatchException extends IllegalStateException {
    public EvidenceMismatchException(String message) {
      super(message);
    }
  }

  public static final class ClaimResult {
    private final ClaimDisposition disposition;
    private final AccountBareLoginExchangeIdentity identity;
    private final AccountBareLoginExchangeOperation operation;

    private ClaimResult(
        ClaimDisposition disposition,
        AccountBareLoginExchangeIdentity identity,
        AccountBareLoginExchangeOperation operation) {
      this.disposition = Objects.requireNonNull(disposition, "disposition");
      this.identity = Objects.requireNonNull(identity, "identity");
      this.operation = Objects.requireNonNull(operation, "operation");
    }

    public ClaimDisposition disposition() {
      return disposition;
    }

    public AccountBareLoginExchangeIdentity identity() {
      return identity;
    }

    public AccountBareLoginExchangeOperation operation() {
      return operation;
    }
  }
}
