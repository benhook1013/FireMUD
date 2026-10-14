package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.InitialLoginDisposalReceipt;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable original-request guard and purpose-specific intent. Never reads active/current authority.
 */
public final class AccountInitialGameplayLoginDisposalRepository {
  private static final String TABLE = "account_initial_gameplay_login_disposals";
  private static final String ISSUANCE = "account_gameplay_delegation_issuance_operations";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Retain the caller-owned transactional DSLContext; copying it would lose the Account transaction boundary.")
  public AccountInitialGameplayLoginDisposalRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Must commit before the first Redis disposal mutation. Caller provenance is receiver-owned. */
  public Intent begin(InitialGameplayLoginDisposalRequest request, String verifiedWorkload) {
    requireTransaction();
    requireDurablePrimary();
    Objects.requireNonNull(request);
    if (verifiedWorkload == null
        || !GameSessionAccountDelegationProfile.GAME_SESSION_CALLER_WORKLOAD
            .matcher(verifiedWorkload)
            .matches()) {
      throw new IllegalArgumentException("Verified original Game Session workload required");
    }
    Record original =
        dsl.fetchOne("SELECT * FROM " + ISSUANCE + " WHERE request_id = ?", request.requestId());
    boolean initiallyAbsent = original == null;
    if (original != null) {
      new AccountGameplayTokenIdentityFenceRepository(dsl)
          .lockAccountForUpdate(original.get("account_uuid", UUID.class));
    }
    lock(request.requestId());
    // Re-read after the same Account-first/request lock ordering used by issuance. A no-row
    // guard commits under the request lock before a delayed prepare can create any candidate.
    original =
        dsl.fetchOne("SELECT * FROM " + ISSUANCE + " WHERE request_id = ?", request.requestId());
    // If preparation won before this lock, retry in Account-first order. Never acquire an
    // Account/issuance-row lock after taking the request-only no-row lock.
    if (initiallyAbsent && original != null) throw unavailable();
    if (original != null)
      original =
          dsl.fetchOne(
              "SELECT * FROM " + ISSUANCE + " WHERE request_id = ? FOR UPDATE",
              request.requestId());
    if (original != null
        && (!verifiedWorkload.equals(original.get("caller_workload", String.class))
            || !request.callerContextId().equals(original.get("caller_context_id", UUID.class))))
      throw conflict();
    byte[] witness = witnessBytes(request);
    Record prior =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", request.requestId());
    if (prior != null) {
      if (!verifiedWorkload.equals(prior.get("caller_workload", String.class))
          || !request.callerContextId().equals(prior.get("caller_context_id", UUID.class))
          || !Arrays.equals(witness, prior.get("witness_canonical_bytes", byte[].class)))
        throw conflict();
      return decode(prior);
    }
    if (request.tokenWitness() != null) {
      var token = request.tokenWitness();
      if (original == null
          || !token.tokenJti().equals(original.get("token_jti", UUID.class))
          || !token.tokenSha256().equals(original.get("token_hash", String.class))
          || token.tokenGeneration() != original.get("token_generation", Long.class)
          || token.issuanceFence() != original.get("issuance_fence", Long.class)) throw conflict();
    }
    UUID operation = UUID.randomUUID();
    Map<String, Object> digestFields = new LinkedHashMap<>();
    digestFields.put("domain", "account-initial-gameplay-login-disposal/v1");
    digestFields.put("disposalOperationId", operation.toString());
    digestFields.put("requestId", request.requestId().toString());
    digestFields.put("callerWorkload", verifiedWorkload);
    digestFields.put("callerContextId", request.callerContextId().toString());
    digestFields.put("witness", new String(witness, StandardCharsets.UTF_8));
    digestFields.put(
        "accountId", original == null ? null : original.get("account_uuid", UUID.class).toString());
    digestFields.put(
        "issuanceOperationId",
        original == null ? null : original.get("operation_id", UUID.class).toString());
    for (String field :
        new String[] {
          "credential_request_digest_version",
          "credential_digest_key_id",
          "credential_request_digest",
          "request_digest",
          "token_jti",
          "token_hash",
          "token_generation",
          "issuance_fence"
        }) {
      Object value = original == null ? null : original.get(field);
      digestFields.put(field, value == null ? null : value.toString());
    }
    String digest = hash(canonical(digestFields));
    String tokenHash = original == null ? null : original.get("token_hash", String.class);
    byte[] pending =
        original == null ? null : original.get("pending_registry_candidate_bytes", byte[].class);
    if ((tokenHash == null) != (pending == null)
        || (original != null
            && tokenHash == null
            && (!"PENDING".equals(original.get("status", String.class))
                || original.get("signer_kid", String.class) != null
                || original.get("signer_generation", String.class) != null))) {
      throw unavailable();
    }
    byte[] tombstone = null;
    if (tokenHash != null) {
      Map<String, Object> marker = new LinkedHashMap<>();
      marker.put("schemaVersion", "initial-gameplay-login-disposal-v1");
      marker.put("state", "revoked");
      marker.put("disposalOperationId", operation.toString());
      marker.put("disposalDigest", digest);
      marker.put("requestId", request.requestId().toString());
      marker.put("issuanceOperationId", original.get("operation_id", UUID.class).toString());
      marker.put("accountId", original.get("account_uuid", UUID.class).toString());
      marker.put("jti", original.get("token_jti", UUID.class).toString());
      marker.put("tokenHash", tokenHash);
      tombstone = canonical(marker);
    }
    dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (request_id, disposal_operation_id, caller_workload, caller_context_id, disposal_digest, witness_canonical_bytes,"
            + " account_uuid, issuance_operation_id, original_token_hash, pending_record_bytes, tombstone_bytes)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.requestId(),
        operation,
        verifiedWorkload,
        request.callerContextId(),
        digest,
        witness,
        original == null ? null : original.get("account_uuid", UUID.class),
        original == null ? null : original.get("operation_id", UUID.class),
        tokenHash,
        pending,
        tombstone);
    return decode(
        dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ?", request.requestId()));
  }

  public InitialGameplayLoginDisposalResult finish(
      Intent intent, InitialLoginDisposalReceipt receipt) {
    requireTransaction();
    requireDurablePrimary();
    Objects.requireNonNull(intent);
    Objects.requireNonNull(receipt);
    lock(intent.requestId());
    Intent current =
        decode(
            dsl.fetchOne(
                "SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", intent.requestId()));
    if (!current.operationId().equals(intent.operationId())
        || !current.digest().equals(intent.digest())
        || !Objects.equals(current.tokenHash(), receipt.tokenHash())
        || !hash(current.tombstoneBytes()).equals(receipt.tombstoneSha256())
        || receipt.localAofCount() < 1L
        || receipt.replicaAofCount() < 1L) throw unavailable();
    if (!current.retired()) {
      byte[] proof =
          canonical(
              Map.of(
                  "schemaVersion",
                  "initial-gameplay-login-disposal-proof-v1",
                  "proofKind",
                  "PHYSICAL_TOKEN_DENIAL",
                  "requestId",
                  intent.requestId().toString(),
                  "disposalOperationId",
                  intent.operationId().toString(),
                  "disposalDigest",
                  intent.digest(),
                  "tokenHash",
                  receipt.tokenHash(),
                  "tombstoneSha256",
                  receipt.tombstoneSha256(),
                  "localAofCount",
                  Long.toString(receipt.localAofCount()),
                  "replicaAofCount",
                  Long.toString(receipt.replicaAofCount())));
      if (dsl.execute(
              "UPDATE "
                  + TABLE
                  + " SET state = 'RETIRED', owner_proof_kind = 'PHYSICAL_TOKEN_DENIAL',"
                  + " owner_proof_canonical_bytes = ?, owner_proof_sha256 = ?, local_aof_count = ?, replica_aof_count = ?"
                  + " WHERE request_id = ? AND state = 'PENDING'",
              proof,
              hash(proof),
              receipt.localAofCount(),
              receipt.replicaAofCount(),
              intent.requestId())
          != 1) {
        throw unavailable();
      }
    }
    Intent readback =
        decode(
            dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ?", intent.requestId()));
    if (!readback.retired()
        || !readback.operationId().equals(current.operationId())
        || !readback.digest().equals(current.digest())) throw unavailable();
    return readback.result(Disposition.RETIRED);
  }

  /**
   * Independent primary readback of the no-key guard and complete current binding shape. This
   * deliberately returns PENDING: immutable current shape is not retained-history/restore
   * qualification, and no protected producer of that qualification currently exists.
   */
  public InitialGameplayLoginDisposalResult readUnqualifiedNoBinding(Intent intent) {
    requireTransaction();
    requireDurablePrimary();
    Objects.requireNonNull(intent);
    if (intent.tokenHash() != null) throw unavailable();
    Record disposal =
        dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ?", intent.requestId());
    if (disposal == null) throw unavailable();
    UUID account = disposal.get("account_uuid", UUID.class);
    if (account != null)
      new AccountGameplayTokenIdentityFenceRepository(dsl).lockAccountForUpdate(account);
    lock(intent.requestId());
    Intent current =
        decode(
            dsl.fetchOne(
                "SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", intent.requestId()));
    if (!current.operationId().equals(intent.operationId())
        || !current.digest().equals(intent.digest())
        || current.tokenHash() != null
        || current.retired()) throw unavailable();
    Record original =
        dsl.fetchOne(
            "SELECT * FROM " + ISSUANCE + " WHERE request_id = ? FOR UPDATE", intent.requestId());
    if (original == null) {
      if (account != null || disposal.get("issuance_operation_id", UUID.class) != null)
        throw unavailable();
    } else {
      if (account == null
          || !account.equals(original.get("account_uuid", UUID.class))
          || !disposal
              .get("issuance_operation_id", UUID.class)
              .equals(original.get("operation_id", UUID.class))
          || !disposal
              .get("caller_context_id", UUID.class)
              .equals(original.get("caller_context_id", UUID.class))
          || !disposal
              .get("caller_workload", String.class)
              .equals(original.get("caller_workload", String.class))
          || !"PENDING".equals(original.get("status", String.class))
          || original.get("token_hash", String.class) != null
          || original.get("signer_kid", String.class) != null
          || original.get("signer_generation", String.class) != null
          || original.get("pending_registry_candidate_bytes", byte[].class) != null
          || original.get("commit_proof_version", Short.class) != null
          || original.get("commit_proof_sha256", String.class) != null
          || original.get("commit_proof_canonical_bytes", byte[].class) != null
          || original.get("committed_at") != null) {
        throw unavailable();
      }
    }
    if (dsl.fetchOne(
                "SELECT request_id FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                intent.requestId())
            != null
        || dsl.fetchOne(
                "SELECT issuance_request_id FROM account_gameplay_token_identity_fences WHERE issuance_request_id = ?",
                intent.requestId())
            != null) {
      throw unavailable();
    }
    // No guessed key/Redis lookup, no invented history boolean, and no claim that signing did not
    // occur. A restored earlier all-null row can satisfy this shape, so it is not terminal proof.
    return current.result(Disposition.PENDING);
  }

  private void requireDurablePrimary() {
    Record primary =
        dsl.fetchOne(
            "SELECT pg_is_in_recovery() AS recovery, current_setting('fsync') AS fsync,"
                + " current_setting('synchronous_commit') AS synchronous_commit");
    if (primary == null
        || !Boolean.FALSE.equals(primary.get("recovery", Boolean.class))
        || !"on".equals(primary.get("fsync", String.class))
        || !java.util.Set.of("on", "remote_apply")
            .contains(primary.get("synchronous_commit", String.class))) throw unavailable();
  }

  private void lock(UUID requestId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "account-gameplay-delegation-request/" + requestId);
  }

  private static byte[] witnessBytes(InitialGameplayLoginDisposalRequest request) {
    var token = request.tokenWitness();
    return token == null
        ? canonical(Map.of("present", false))
        : canonical(
            Map.of(
                "present",
                true,
                "jti",
                token.tokenJti().toString(),
                "tokenHash",
                token.tokenSha256(),
                "tokenGeneration",
                Long.toString(token.tokenGeneration()),
                "issuanceFence",
                Long.toString(token.issuanceFence())));
  }

  private static Intent decode(Record row) {
    if (row == null) throw unavailable();
    String state = row.get("state", String.class);
    String kind = row.get("owner_proof_kind", String.class);
    byte[] proof = row.get("owner_proof_canonical_bytes", byte[].class);
    String proofHash = row.get("owner_proof_sha256", String.class);
    if (!("PENDING".equals(state) && "NONE".equals(kind) && proof == null && proofHash == null)
        && !("RETIRED".equals(state)
            && "PHYSICAL_TOKEN_DENIAL".equals(kind)
            && proof != null
            && proof.length > 0
            && proof.length <= 4096
            && hash(proof).equals(proofHash))) throw unavailable();
    if ("RETIRED".equals(state)) {
      Long local = row.get("local_aof_count", Long.class);
      Long replica = row.get("replica_aof_count", Long.class);
      byte[] marker = row.get("tombstone_bytes", byte[].class);
      if (local == null || local < 1L || replica == null || replica < 1L || marker == null)
        throw unavailable();
      byte[] expected =
          canonical(
              Map.of(
                  "schemaVersion",
                  "initial-gameplay-login-disposal-proof-v1",
                  "proofKind",
                  "PHYSICAL_TOKEN_DENIAL",
                  "requestId",
                  row.get("request_id", UUID.class).toString(),
                  "disposalOperationId",
                  row.get("disposal_operation_id", UUID.class).toString(),
                  "disposalDigest",
                  row.get("disposal_digest", String.class),
                  "tokenHash",
                  row.get("original_token_hash", String.class),
                  "tombstoneSha256",
                  hash(marker),
                  "localAofCount",
                  Long.toString(local),
                  "replicaAofCount",
                  Long.toString(replica)));
      if (!Arrays.equals(expected, proof)) throw unavailable();
    }
    return new Intent(
        row.get("request_id", UUID.class),
        row.get("caller_context_id", UUID.class),
        row.get("disposal_operation_id", UUID.class),
        row.get("disposal_digest", String.class),
        row.get("original_token_hash", String.class),
        row.get("pending_record_bytes", byte[].class),
        row.get("tombstone_bytes", byte[].class),
        "RETIRED".equals(row.get("state", String.class)));
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException failure) {
      throw unavailable();
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
  }

  private static DisposalUnavailableException unavailable() {
    return new DisposalUnavailableException();
  }

  private static DisposalConflictException conflict() {
    return new DisposalConflictException();
  }

  public static final class DisposalUnavailableException extends IllegalStateException {
    public DisposalUnavailableException() {
      super("Initial LOGIN disposal is unavailable");
    }
  }

  public static final class DisposalConflictException extends IllegalStateException {
    public DisposalConflictException() {
      super("Original LOGIN disposal identity conflicts");
    }
  }

  /** Only this SQL owner constructs mutation authority, after persisting the immutable intent. */
  public static final class Intent {
    private final UUID requestId;
    private final UUID contextId;
    private final UUID operationId;
    private final String digest;
    private final String tokenHash;
    private final byte[] pending;
    private final byte[] tombstone;
    private final boolean retired;

    private Intent(
        UUID requestId,
        UUID contextId,
        UUID operationId,
        String digest,
        String tokenHash,
        byte[] pending,
        byte[] tombstone,
        boolean retired) {
      this.requestId = requestId;
      this.contextId = contextId;
      this.operationId = operationId;
      this.digest = digest;
      this.tokenHash = tokenHash;
      this.pending = pending == null ? null : pending.clone();
      this.tombstone = tombstone == null ? null : tombstone.clone();
      this.retired = retired;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID operationId() {
      return operationId;
    }

    public String digest() {
      return digest;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public byte[] pendingBytes() {
      return pending == null ? null : pending.clone();
    }

    public byte[] tombstoneBytes() {
      return tombstone == null ? null : tombstone.clone();
    }

    public boolean retired() {
      return retired;
    }

    public InitialGameplayLoginDisposalResult result(Disposition disposition) {
      return new InitialGameplayLoginDisposalResult(requestId, contextId, operationId, disposition);
    }

    @Override
    public String toString() {
      return "InitialLoginDisposalIntent[redacted]";
    }
  }
}
