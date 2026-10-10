package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiPrimaryIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Genuine durable control-ui owner state. No caller-provided registry or evidence is accepted. */
public final class AccountControlUiIssuanceRepository {
  private static final String TABLE = "account_control_ui_issuance_operations";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final DSLContext dsl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected DSLContext is an internal Account transaction collaborator.")
  public AccountControlUiIssuanceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  Stored findRequest(UUID requestId) {
    requireTransaction();
    Record row = dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ?", requestId);
    return row == null ? null : new Stored(row);
  }

  Stored findToken(String tokenHash) {
    requireTransaction();
    Record row = dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE token_hash = ?", tokenHash);
    return row == null ? null : new Stored(row);
  }

  /** Finds the one committed original control-ui issuance for an exact actor and token identity. */
  Stored findCommittedByTokenJti(UUID accountId, UUID tenantId, UUID tokenJti) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + TABLE
                + " WHERE account_uuid = ? AND tenant_uuid = ? AND token_jti = ?"
                + " AND status = 'COMMITTED'",
            accountId,
            tenantId,
            tokenJti);
    if (row == null) {
      return null;
    }
    Stored stored = new Stored(row);
    if (!accountId.equals(stored.accountId)
        || !tenantId.equals(stored.tenantId)
        || !tokenJti.equals(stored.jti)
        || !"COMMITTED".equals(stored.status)) {
      throw denied();
    }
    return stored;
  }

  /** Locks and exact-compares the original committed operation after external evidence reads. */
  Stored lockCommittedByTokenJti(Stored original) {
    requireTransaction();
    Objects.requireNonNull(original, "original committed control-ui issuance is required");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE operation_id = ? AND request_id = ? FOR UPDATE",
            original.operationId,
            original.requestId);
    if (row == null) {
      throw denied();
    }
    Stored current = new Stored(row);
    if (!sameCommittedOperation(original, current)) {
      throw denied();
    }
    return current;
  }

  private static boolean sameCommittedOperation(Stored original, Stored current) {
    return "COMMITTED".equals(original.status)
        && "COMMITTED".equals(current.status)
        && Objects.equals(original.requestId, current.requestId)
        && Objects.equals(original.operationId, current.operationId)
        && Objects.equals(original.jti, current.jti)
        && Objects.equals(original.accountId, current.accountId)
        && Objects.equals(original.tenantId, current.tenantId)
        && Objects.equals(original.caller, current.caller)
        && Objects.equals(original.callerContextId, current.callerContextId)
        && Objects.equals(original.requestMacKeyId, current.requestMacKeyId)
        && Objects.equals(original.requestDigest, current.requestDigest)
        && Objects.equals(original.tokenHash, current.tokenHash)
        && Arrays.equals(original.claims, current.claims)
        && Arrays.equals(original.sources, current.sources)
        && Arrays.equals(original.bundle, current.bundle)
        && Arrays.equals(original.signerReceipt, current.signerReceipt)
        && Arrays.equals(original.pendingRegistry, current.pendingRegistry)
        && Arrays.equals(original.activeRegistry, current.activeRegistry)
        && Objects.equals(original.issuedAt, current.issuedAt)
        && Objects.equals(original.expiresAt, current.expiresAt)
        && Objects.equals(original.recoveryExpiry, current.recoveryExpiry);
  }

  Stored prepare(
      UUID requestId,
      String caller,
      UUID callerContextId,
      String macKeyId,
      String digest,
      ControlUiPrimaryIdentity authenticated,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiSignerOwner.Capture signer,
      Instant issuedAt) {
    requireTransaction();
    if (!authenticated.accountId().equals(source.actor())) {
      throw denied();
    }
    UUID operationId = UUID.randomUUID();
    UUID jti = UUID.randomUUID();
    Instant expiry = issuedAt.plusSeconds(300);
    Instant recovery = issuedAt.plusSeconds(60);
    var spec =
        new AccountControlUiSigningSpec(operationId, requestId, jti, source, issuedAt, expiry);
    var transaction =
        dsl.fetchOne(
            "SELECT pg_current_xact_id()::TEXT AS xid, nextval('account_control_ui_source_fence') AS fence");
    if (transaction == null) {
      throw denied();
    }
    String xid = transaction.get("xid", String.class);
    Long fence = transaction.get("fence", Long.class);
    if (xid == null || !xid.matches("[1-9][0-9]{0,19}") || fence == null || fence <= 0) {
      throw denied();
    }
    Map<String, Object> ref =
        Map.of(
            "bundleVersion",
            "1",
            "sourceVersion",
            xid,
            "sourceFence",
            Long.toString(fence),
            "linearization",
            xid);
    Map<String, Object> operation =
        Map.of(
            "operationId",
            operationId.toString(),
            "requestId",
            requestId.toString(),
            "requestDigest",
            digest,
            "callerWorkload",
            caller,
            "callerContextId",
            callerContextId.toString(),
            "accountId",
            source.actor().toString());
    var bundle = new LinkedHashMap<String, Object>();
    bundle.put("schema", "account-auth-evidence-bundle/v1");
    bundle.put("bundleRef", ref);
    bundle.put("snapshotIdentity", hash(source.evidence()));
    bundle.put(
        "evaluationIdentity",
        hash(
            AccountControlUiAuthority.canonical(
                Map.of(
                    "operation",
                    operation,
                    "claims",
                    spec.claims(),
                    "bundleRef",
                    ref,
                    "snapshotIdentity",
                    hash(source.evidence())))));
    bundle.put("issuer", "firemud-account-service");
    bundle.put("profile", "control-ui");
    bundle.put("audience", "control-ui");
    bundle.put(
        "scope",
        Map.of(
            "kind",
            "TENANT",
            "accountId",
            source.actor().toString(),
            "tenantIds",
            java.util.List.of(source.tenant().toString())));
    bundle.put("operation", operation);
    bundle.put(
        "tokenIdentity",
        Map.of(
            "jti",
            jti.toString(),
            "tokenGeneration",
            1,
            "iat",
            issuedAt.getEpochSecond(),
            "nbf",
            issuedAt.getEpochSecond(),
            "exp",
            expiry.getEpochSecond()));
    bundle.put("authorityTuple", source.authorityTuple());
    bundle.put("membershipVersion", source.membershipVersion());
    bundle.put("issuanceFence", source.issuanceFence());
    Map<String, Object> versions = new LinkedHashMap<>();
    source.sources().forEach(value -> versions.put(value.key(), value.sourceVersion()));
    bundle.put("authoritySourceVersions", Map.copyOf(versions));
    bundle.put("accountIdentitySource", source.accountIdentitySource());
    bundle.put("outboxCheckpoints", source.outboxCheckpoints());
    bundle.put(
        "roleEvidence",
        Map.of(
            "scopedRoles",
            spec.claims().get("scopedRoles"),
            "freshnessSource",
            hash(source.evidence())));
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (request_id, operation_id, token_jti, account_uuid, tenant_uuid, caller_workload,"
                + " caller_context_id, request_mac_key_id, request_digest, claims_payload, source_payload,"
                + " bundle_payload, signer_receipt, issued_at_epoch_second, expires_at_epoch_second,"
                + " recovery_expires_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED')"
                + " ON CONFLICT (request_id) DO NOTHING",
            requestId,
            operationId,
            jti,
            source.actor(),
            source.tenant(),
            caller,
            callerContextId,
            macKeyId,
            digest,
            AccountControlUiAuthority.canonical(spec.claims()),
            source.evidence(),
            AccountControlUiAuthority.canonical(bundle),
            signer.receipt(),
            issuedAt.getEpochSecond(),
            expiry.getEpochSecond(),
            OffsetDateTime.ofInstant(recovery, java.time.ZoneOffset.UTC));
    Stored stored = findRequest(requestId);
    if (inserted != 1 || stored == null || !operationId.equals(stored.operationId)) {
      throw denied();
    }
    // The complete fixed-width digest field permits both final registry sizes to be checked now,
    // before any private signing or Coordination mutation. No placeholder is retained as evidence.
    registry(stored, signer, "0".repeat(64), false);
    registry(stored, signer, "0".repeat(64), true);
    return stored;
  }

  Stored bindCandidate(
      Stored original,
      String tokenHash,
      byte[] envelope,
      byte[] binding,
      AccountControlUiSignerOwner.Capture signer) {
    requireTransaction();
    Stored current = lockExact(original);
    if (!Arrays.equals(current.signerReceipt, signer.receipt())
        || !"PREPARED".equals(current.status)) {
      throw denied();
    }
    byte[] pending = registry(current, signer, tokenHash, false);
    byte[] active = registry(current, signer, tokenHash, true);
    dsl.execute(
        "INSERT INTO account_control_ui_response_envelopes"
            + " (operation_id, encrypted_response, owner_binding, recovery_expires_at) VALUES (?, ?, ?, ?::timestamptz)",
        current.operationId,
        envelope,
        binding,
        OffsetDateTime.ofInstant(current.recoveryExpiry, java.time.ZoneOffset.UTC));
    if (dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'CANDIDATE', token_hash = ?, pending_registry = ?, active_registry = ?"
                + " WHERE operation_id = ? AND status = 'PREPARED'",
            tokenHash,
            pending,
            active,
            current.operationId)
        != 1) {
      throw denied();
    }
    return findRequest(current.requestId);
  }

  Committed commit(Stored original, AccountControlUiCoordination.Receipt pending) {
    requireTransaction();
    Stored current = lockExact(original);
    if (!Objects.equals(current.tokenHash, pending.tokenHash)
        || !hash(current.pendingRegistry).equals(pending.recordDigest)
        || current.expiryMillis() != pending.expiryMillis
        || !"CANDIDATE".equals(current.status)) {
      throw denied();
    }
    byte[] receipt =
        AccountControlUiAuthority.canonical(
            Map.of(
                "schema",
                "account-control-ui-pending-readback/v1",
                "tokenHash",
                pending.tokenHash,
                "recordDigest",
                pending.recordDigest,
                "expiryMillis",
                pending.expiryMillis,
                "aclIdentity",
                "account_coord_app",
                "localAofCount",
                pending.localAof,
                "replicaAofCount",
                pending.replicas));
    if (dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'COMMITTED', pending_receipt = ?, committed_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND status = 'CANDIDATE'",
            receipt,
            current.operationId)
        != 1) {
      throw denied();
    }
    return requireCommitted(findRequest(current.requestId));
  }

  Committed requireCommitted(Stored original) {
    Stored current = lockExact(original);
    if (!"COMMITTED".equals(current.status)) {
      throw denied();
    }
    return new Committed(current);
  }

  /** Durable non-authorizing intent precedes any external revocation; no source recapture. */
  Stored beginRecoveryFailure(Stored original) {
    Stored current = lockExact(original);
    if (java.util.List.of("FAILED", "REVOKING", "REVOKED").contains(current.status)) {
      return current;
    }
    String next = current.tokenHash == null ? "FAILED" : "REVOKING";
    if (dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = ?, recovery_failed_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND status = ?",
            next,
            current.operationId,
            current.status)
        != 1) {
      throw denied();
    }
    return findRequest(current.requestId);
  }

  void finishRecoveryFailure(Stored original, AccountControlUiCoordination.Receipt receipt) {
    Stored current = lockExact(original);
    if (!"REVOKING".equals(current.status)
        || !Objects.equals(current.tokenHash, receipt.tokenHash)
        || !hash(revokedRegistry(current)).equals(receipt.recordDigest)
        || current.expiryMillis() != receipt.expiryMillis) {
      throw denied();
    }
    byte[] evidence =
        AccountControlUiAuthority.canonical(
            Map.of(
                "schema",
                "account-control-ui-revoked-readback/v1",
                "tokenHash",
                receipt.tokenHash,
                "recordDigest",
                receipt.recordDigest,
                "expiryMillis",
                receipt.expiryMillis,
                "aclIdentity",
                "account_coord_app",
                "localAofCount",
                receipt.localAof,
                "replicaAofCount",
                receipt.replicas));
    if (dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'REVOKED', revocation_receipt = ?"
                + " WHERE operation_id = ? AND status = 'REVOKING'",
            evidence,
            current.operationId)
        != 1) {
      throw denied();
    }
  }

  static byte[] revokedRegistry(Stored original) {
    if (!"REVOKING".equals(original.status)) {
      throw denied();
    }
    var record = new LinkedHashMap<String, Object>(object(original.pendingRegistry));
    record.put("state", "revoked");
    record.put("registryVersion", 3);
    return AccountControlUiAuthority.canonical(record);
  }

  Envelope envelope(Stored original) {
    requireTransaction();
    Record value =
        dsl.fetchOne(
            "SELECT encrypted_response, owner_binding, recovery_expires_at"
                + " FROM account_control_ui_response_envelopes WHERE operation_id = ?",
            original.operationId);
    if (value == null) {
      return null;
    }
    if (!value
        .get("recovery_expires_at", OffsetDateTime.class)
        .toInstant()
        .equals(original.recoveryExpiry)) {
      return null;
    }
    return new Envelope(
        value.get("encrypted_response", byte[].class), value.get("owner_binding", byte[].class));
  }

  private Stored lockExact(Stored original) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE operation_id = ? FOR UPDATE", original.operationId);
    if (row == null) {
      throw denied();
    }
    Stored current = new Stored(row);
    if (!original.requestId.equals(current.requestId)
        || !Arrays.equals(original.claims, current.claims)
        || !Arrays.equals(original.sources, current.sources)
        || !Arrays.equals(original.bundle, current.bundle)
        || !Arrays.equals(original.signerReceipt, current.signerReceipt)
        || !original.requestDigest.equals(current.requestDigest)) {
      throw denied();
    }
    return current;
  }

  private static byte[] registry(
      Stored value, AccountControlUiSignerOwner.Capture signer, String tokenHash, boolean active) {
    var claims = object(value.claims);
    var bundle = object(value.bundle);
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", 1);
    result.put("registryVersion", active ? 2 : 1);
    result.put("tokenHash", tokenHash);
    result.put("kid", signer.kid());
    result.put("signerGeneration", signer.generation());
    result.put("issuer", "firemud-account-service");
    result.put("profile", "control-ui");
    result.put("type", "control-ui");
    result.put("audience", "control-ui");
    for (String field :
        java.util.List.of(
            "accountId",
            "jti",
            "iat",
            "nbf",
            "exp",
            "tokenGeneration",
            "authorityTuple",
            "membershipVersion",
            "issuanceFence")) {
      result.put(field, claims.get(field));
    }
    result.put("operationId", value.operationId.toString());
    result.put("requestId", value.requestId.toString());
    result.put("requestDigest", value.requestDigest);
    result.put("state", active ? "active" : "pending");
    result.put("authoritySourceVersions", bundle.get("authoritySourceVersions"));
    var reference = new LinkedHashMap<String, Object>();
    @SuppressWarnings("unchecked")
    var bundleRef = (Map<String, Object>) bundle.get("bundleRef");
    reference.putAll(bundleRef);
    reference.put("canonicalSha256", hash(value.bundle));
    result.put("authEvidenceBundle", Map.copyOf(reference));
    result.put("originalSignerReceiptDigest", hash(value.signerReceipt));
    byte[] bytes = AccountControlUiAuthority.canonical(result);
    if (bytes.length > 32768) {
      throw denied();
    }
    return bytes;
  }

  static Map<String, Object> object(byte[] bytes) {
    try {
      return JSON.readValue(new String(bytes, StandardCharsets.UTF_8), new TypeReference<>() {});
    } catch (RuntimeException failure) {
      throw denied();
    }
  }

  static String hash(byte[] bytes) {
    return AccountControlUiCoordination.digest("SHA-256", bytes);
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw denied();
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw denied();
          }
        });
  }

  static final class Stored {
    final UUID requestId, operationId, jti, accountId, tenantId, callerContextId;
    final String caller, requestMacKeyId, requestDigest, status, tokenHash;
    final byte[] claims, sources, bundle, signerReceipt, pendingRegistry, activeRegistry;
    final Instant issuedAt, expiresAt, recoveryExpiry;

    Stored(Record row) {
      requestId = row.get("request_id", UUID.class);
      operationId = row.get("operation_id", UUID.class);
      jti = row.get("token_jti", UUID.class);
      accountId = row.get("account_uuid", UUID.class);
      tenantId = row.get("tenant_uuid", UUID.class);
      callerContextId = row.get("caller_context_id", UUID.class);
      caller = row.get("caller_workload", String.class);
      requestMacKeyId = row.get("request_mac_key_id", String.class);
      requestDigest = row.get("request_digest", String.class);
      status = row.get("status", String.class);
      tokenHash = row.get("token_hash", String.class);
      claims = row.get("claims_payload", byte[].class);
      sources = row.get("source_payload", byte[].class);
      bundle = row.get("bundle_payload", byte[].class);
      signerReceipt = row.get("signer_receipt", byte[].class);
      pendingRegistry = row.get("pending_registry", byte[].class);
      activeRegistry = row.get("active_registry", byte[].class);
      issuedAt = Instant.ofEpochSecond(row.get("issued_at_epoch_second", Long.class));
      expiresAt = Instant.ofEpochSecond(row.get("expires_at_epoch_second", Long.class));
      recoveryExpiry = row.get("recovery_expires_at", OffsetDateTime.class).toInstant();
    }

    long expiryMillis() {
      return expiresAt.toEpochMilli();
    }

    @Override
    public String toString() {
      return "AccountControlUiIssuanceRepository.Stored[redacted]";
    }
  }

  public static final class Committed {
    final Stored stored;

    private Committed(Stored stored) {
      this.stored = stored;
    }

    public String tokenHash() {
      return stored.tokenHash;
    }

    public byte[] pendingRegistry() {
      return stored.pendingRegistry.clone();
    }

    public byte[] activeRegistry() {
      return stored.activeRegistry.clone();
    }

    public long expiryMillis() {
      return stored.expiryMillis();
    }

    @Override
    public String toString() {
      return "AccountControlUiIssuanceRepository.Committed[redacted]";
    }
  }

  record Envelope(byte[] encrypted, byte[] binding) {
    Envelope {
      encrypted = encrypted.clone();
      binding = binding.clone();
    }

    @Override
    public byte[] encrypted() {
      return encrypted.clone();
    }

    @Override
    public byte[] binding() {
      return binding.clone();
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact Account control-ui issuance owner evidence required");
  }
}
