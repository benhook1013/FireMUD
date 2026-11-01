package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-owned immutable reauthentication evidence and one-time operation-consumption storage. */
public final class AccountRecentReauthenticationRepository {
  private static final String EVIDENCE = "account_recent_reauthentication_evidence";
  private static final String CONSUMPTIONS = "account_recent_reauthentication_consumptions";
  private final DSLContext dsl;

  public AccountRecentReauthenticationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "Account reauthentication SQL owner is required");
  }

  void lockRequest(UUID requestId) {
    requireWritableTransaction();
    lockIdentity("recent-reauth-request", requestId);
  }

  void lockCreationOperation(UUID creationOperationId) {
    requireWritableTransaction();
    lockIdentity("recent-reauth-creation", creationOperationId);
  }

  Optional<AccountRecentReauthenticationEvidence> findByRequest(UUID requestId) {
    requireTransaction();
    return Optional.ofNullable(
        map(dsl.fetchOne("SELECT * FROM " + EVIDENCE + " WHERE request_id = ?", requestId)));
  }

  Optional<AccountRecentReauthenticationEvidence> findByCreationOperation(UUID operationId) {
    requireTransaction();
    return Optional.ofNullable(
        map(
            dsl.fetchOne(
                "SELECT * FROM " + EVIDENCE + " WHERE creation_operation_id = ?", operationId)));
  }

  void insert(
      AccountRecentReauthenticationEvidence evidence, byte[] requestPayload, ReplayProof proof) {
    requireWritableTransaction();
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(proof);
    byte[] exactPayload = Objects.requireNonNull(requestPayload).clone();
    if (!evidence.requestDigest().equals(digest(exactPayload))) {
      throw unavailable();
    }
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + EVIDENCE
                + " (evidence_id, request_id, account_uuid, purpose, creation_operation_id, "
                + "request_payload, request_digest, identity_provenance, "
                + "account_uuid_source_numeric_id, account_lifecycle_state, "
                + "account_authority_generation, account_authority_source_version, issuance_fence, "
                + "issuance_fence_source_version, platform_policy_version, deployment_policy_version, "
                + "effective_policy_version, recent_age_seconds, max_clock_skew_seconds, issued_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
            evidence.evidenceId(),
            evidence.requestId(),
            evidence.accountId(),
            evidence.purpose(),
            evidence.creationOperationId(),
            exactPayload,
            evidence.requestDigest(),
            evidence.identityProvenance().name(),
            evidence.identitySourceNumericId(),
            evidence.lifecycleState().name(),
            evidence.accountAuthorityGeneration(),
            evidence.accountAuthoritySourceVersion(),
            evidence.issuanceFence(),
            evidence.issuanceFenceSourceVersion(),
            evidence.platformPolicyVersion(),
            evidence.deploymentPolicyVersion(),
            evidence.effectivePolicyVersion(),
            evidence.recentAgeSeconds(),
            evidence.maxClockSkewSeconds(),
            OffsetDateTime.ofInstant(evidence.issuedAt(), ZoneOffset.UTC));
    if (inserted != 1) {
      throw unavailable();
    }
    if (dsl.execute(
            "INSERT INTO account_recent_reauthentication_replay_credentials"
                + " (evidence_id, request_id, account_uuid, purpose, creation_operation_id, key_id, credential_mac)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
            evidence.evidenceId(),
            evidence.requestId(),
            evidence.accountId(),
            evidence.purpose(),
            evidence.creationOperationId(),
            proof.keyId(),
            proof.mac())
        != 1) {
      throw unavailable();
    }
    ReplayProof retained =
        findReplayProof(evidence).orElseThrow(AccountRecentReauthenticationRepository::unavailable);
    if (!proof.keyId().equals(retained.keyId())
        || !MessageDigest.isEqual(
            proof.mac().getBytes(StandardCharsets.US_ASCII),
            retained.mac().getBytes(StandardCharsets.US_ASCII))) {
      throw unavailable();
    }
    AccountRecentReauthenticationEvidence readback =
        findByRequest(evidence.requestId())
            .orElseThrow(AccountRecentReauthenticationRepository::unavailable);
    if (!sameEvidence(evidence, readback)) {
      throw unavailable();
    }
  }

  Optional<ReplayProof> findReplayProof(AccountRecentReauthenticationEvidence evidence) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT key_id, credential_mac FROM account_recent_reauthentication_replay_credentials"
                + " WHERE evidence_id = ? AND request_id = ? AND account_uuid = ? AND purpose = ? AND creation_operation_id = ?",
            evidence.evidenceId(),
            evidence.requestId(),
            evidence.accountId(),
            evidence.purpose(),
            evidence.creationOperationId());
    return row == null
        ? Optional.empty()
        : Optional.of(
            new ReplayProof(
                row.get("key_id", String.class), row.get("credential_mac", String.class)));
  }

  AccountRecentReauthenticationEvidence readExactOriginal(
      AccountRecentReauthenticationEvidence expected) {
    var stored =
        findByRequest(expected.requestId())
            .orElseThrow(AccountRecentReauthenticationRepository::unavailable);
    if (!sameEvidence(expected, stored)) throw unavailable();
    return stored;
  }

  /** Retained keyed proof only. Never include credential material or its MAC in diagnostics. */
  static final class ReplayProof {
    private final String keyId;
    private final String mac;

    ReplayProof(String keyId, String mac) {
      if (keyId == null
          || !keyId.matches("[A-Za-z0-9_-]{1,32}")
          || mac == null
          || !mac.matches("[0-9a-f]{64}")) {
        throw unavailable();
      }
      this.keyId = keyId;
      this.mac = mac;
    }

    String keyId() {
      return keyId;
    }

    String mac() {
      return mac;
    }

    @Override
    public String toString() {
      return "AccountRecentReauthenticationRepository.ReplayProof[redacted]";
    }
  }

  AccountRecentReauthenticationEvidence lockExact(AccountRecentReauthenticationEvidence presented) {
    requireWritableTransaction();
    Objects.requireNonNull(presented);
    AccountRecentReauthenticationEvidence stored =
        map(
            dsl.fetchOne(
                "SELECT * FROM " + EVIDENCE + " WHERE evidence_id = ? FOR UPDATE",
                presented.evidenceId()));
    if (stored == null || !sameEvidence(stored, presented)) {
      throw unavailable();
    }
    return stored;
  }

  Optional<Consumption> findConsumption(UUID evidenceId) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT evidence_id, request_id, account_uuid, purpose, creation_operation_id, "
                + "consumed_at FROM "
                + CONSUMPTIONS
                + " WHERE evidence_id = ?",
            evidenceId);
    return Optional.ofNullable(row == null ? null : new Consumption(row));
  }

  Consumption insertConsumption(
      AccountRecentReauthenticationEvidence evidence, Instant consumedAt) {
    requireWritableTransaction();
    Objects.requireNonNull(evidence);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + CONSUMPTIONS
                + " (evidence_id, request_id, account_uuid, purpose, creation_operation_id, consumed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::timestamptz)",
            evidence.evidenceId(),
            evidence.requestId(),
            evidence.accountId(),
            evidence.purpose(),
            evidence.creationOperationId(),
            OffsetDateTime.ofInstant(consumedAt, ZoneOffset.UTC));
    if (inserted != 1) {
      throw unavailable();
    }
    Consumption stored =
        findConsumption(evidence.evidenceId())
            .orElseThrow(AccountRecentReauthenticationRepository::unavailable);
    if (!stored.matches(evidence)) {
      throw unavailable();
    }
    return stored;
  }

  static byte[] requestPayload(UUID requestId, UUID accountId, String purpose, UUID operationId) {
    String payload =
        "account-recent-reauthentication-request/v1\n"
            + "requestId="
            + requestId
            + "\naccountId="
            + accountId
            + "\npurpose="
            + purpose
            + "\ncreationOperationId="
            + operationId
            + "\n";
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  static String digest(byte[] payload) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable for Account evidence", unavailable);
    }
  }

  private void lockIdentity(String namespace, UUID identity) {
    Objects.requireNonNull(identity);
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, ?))",
        identity,
        namespace.hashCode());
  }

  private static AccountRecentReauthenticationEvidence map(Record row) {
    if (row == null) {
      return null;
    }
    try {
      UUID requestId = required(row, "request_id", UUID.class);
      UUID accountId = required(row, "account_uuid", UUID.class);
      String purpose = required(row, "purpose", String.class);
      UUID operationId = required(row, "creation_operation_id", UUID.class);
      byte[] payload = required(row, "request_payload", byte[].class);
      String requestDigest = required(row, "request_digest", String.class);
      if (!requestDigest.equals(digest(payload))
          || !java.util.Arrays.equals(
              payload, requestPayload(requestId, accountId, purpose, operationId))) {
        throw unavailable();
      }
      OffsetDateTime issuedAt = required(row, "issued_at", OffsetDateTime.class);
      return new AccountRecentReauthenticationEvidence(
          required(row, "evidence_id", UUID.class),
          requestId,
          accountId,
          purpose,
          operationId,
          requestDigest,
          AccountIdentityProvenance.fromStorageValue(
              required(row, "identity_provenance", String.class)),
          requiredPositive(row, "account_uuid_source_numeric_id"),
          AccountLifecycleState.valueOf(required(row, "account_lifecycle_state", String.class)),
          requiredPositive(row, "account_authority_generation"),
          requiredPositive(row, "account_authority_source_version"),
          requiredPositive(row, "issuance_fence"),
          requiredPositive(row, "issuance_fence_source_version"),
          requiredPositive(row, "platform_policy_version"),
          requiredPositive(row, "deployment_policy_version"),
          required(row, "effective_policy_version", String.class),
          requiredPositive(row, "recent_age_seconds"),
          requiredNonnegative(row, "max_clock_skew_seconds"),
          issuedAt.toInstant());
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      throw unavailable();
    }
  }

  private static boolean sameEvidence(
      AccountRecentReauthenticationEvidence first, AccountRecentReauthenticationEvidence second) {
    return first.evidenceId().equals(second.evidenceId())
        && first.requestId().equals(second.requestId())
        && first.accountId().equals(second.accountId())
        && first.purpose().equals(second.purpose())
        && first.creationOperationId().equals(second.creationOperationId())
        && first.requestDigest().equals(second.requestDigest())
        && first.identityProvenance() == second.identityProvenance()
        && first.identitySourceNumericId() == second.identitySourceNumericId()
        && first.lifecycleState() == second.lifecycleState()
        && first.accountAuthorityGeneration() == second.accountAuthorityGeneration()
        && first.accountAuthoritySourceVersion() == second.accountAuthoritySourceVersion()
        && first.issuanceFence() == second.issuanceFence()
        && first.issuanceFenceSourceVersion() == second.issuanceFenceSourceVersion()
        && first.platformPolicyVersion() == second.platformPolicyVersion()
        && first.deploymentPolicyVersion() == second.deploymentPolicyVersion()
        && first.effectivePolicyVersion().equals(second.effectivePolicyVersion())
        && first.recentAgeSeconds() == second.recentAgeSeconds()
        && first.maxClockSkewSeconds() == second.maxClockSkewSeconds()
        && first.issuedAt().equals(second.issuedAt());
  }

  private static long requiredPositive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw unavailable();
    }
    return value;
  }

  private static long requiredNonnegative(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value < 0L) {
      throw unavailable();
    }
    return value;
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw unavailable();
    }
    return value;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Account evidence reads require an owner transaction");
    }
  }

  private static void requireWritableTransaction() {
    requireTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Account evidence mutation requires a writable transaction");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact Account reauthentication evidence is unavailable");
  }

  static final class Consumption {
    private final UUID evidenceId;
    private final UUID requestId;
    private final UUID accountId;
    private final String purpose;
    private final UUID creationOperationId;
    private final Instant consumedAt;

    private Consumption(Record row) {
      evidenceId = row.get("evidence_id", UUID.class);
      requestId = row.get("request_id", UUID.class);
      accountId = row.get("account_uuid", UUID.class);
      purpose = row.get("purpose", String.class);
      creationOperationId = row.get("creation_operation_id", UUID.class);
      OffsetDateTime timestamp = row.get("consumed_at", OffsetDateTime.class);
      consumedAt = timestamp == null ? null : timestamp.toInstant();
      if (evidenceId == null
          || requestId == null
          || accountId == null
          || purpose == null
          || creationOperationId == null
          || consumedAt == null) {
        throw unavailable();
      }
    }

    boolean matches(AccountRecentReauthenticationEvidence evidence) {
      return evidenceId.equals(evidence.evidenceId())
          && requestId.equals(evidence.requestId())
          && accountId.equals(evidence.accountId())
          && purpose.equals(evidence.purpose())
          && creationOperationId.equals(evidence.creationOperationId());
    }

    @Override
    public String toString() {
      return "AccountRecentReauthenticationRepository.Consumption[redacted]";
    }
  }
}
