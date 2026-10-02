package net.firedevops.firemud.worldmanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "The injected DSLContext is shared Spring infrastructure for repository transaction participation.")
public class InitialAdmissionBindHoldRepository {
  private static final String SELECT_COLUMNS =
      "hold_id, hold_fence, tenant_id, realm_uuid, playable_state_namespace_uuid, "
          + "playable_state_scope, game_instance_id, version_id, active_lifecycle_epoch, "
          + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
          + "expected_catalog_revision, status, diagnostic_expires_at, owner_proof_id, "
          + "owner_proof_digest, owner_pointer_audit_id, owner_pointer_version, "
          + "reconciliation_error, created_at, updated_at, terminal_at, row_version";

  private final DSLContext dsl;

  public InitialAdmissionBindHoldRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<InitialAdmissionBindHold> findByTenantIdAndRequestId(
      long tenantId, String initialAdmissionRequestId) {
    return one(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM initial_admission_bind_hold WHERE tenant_id = ? "
            + "AND initial_admission_request_id = ?",
        tenantId,
        initialAdmissionRequestId);
  }

  public Optional<InitialAdmissionBindHold> findByHoldId(String holdId) {
    return one(
        "SELECT " + SELECT_COLUMNS + " FROM initial_admission_bind_hold WHERE hold_id = ?::uuid",
        UUID.fromString(holdId));
  }

  public Optional<InitialAdmissionBindHold> findByHoldIdForUpdate(String holdId) {
    return one(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM initial_admission_bind_hold WHERE hold_id = ?::uuid FOR UPDATE",
        UUID.fromString(holdId));
  }

  public List<InitialAdmissionBindHold> findNonterminal(int limit) {
    Result<Record> rows =
        dsl.fetch(
            "SELECT "
                + SELECT_COLUMNS
                + " FROM initial_admission_bind_hold WHERE status IN "
                + "('PENDING', 'RECONCILIATION_REQUIRED') ORDER BY updated_at, hold_id LIMIT ?",
            Math.max(1, Math.min(limit, 256)));
    return rows.map(this::toEntity);
  }

  public boolean hasNonterminalForRealm(long tenantId, String realmUuid) {
    return Boolean.TRUE.equals(
        dsl.fetchValue(
            "SELECT EXISTS (SELECT 1 FROM initial_admission_bind_hold "
                + "WHERE tenant_id = ? AND realm_uuid = ?::uuid "
                + "AND status IN ('PENDING', 'RECONCILIATION_REQUIRED'))",
            Boolean.class,
            tenantId,
            UUID.fromString(realmUuid)));
  }

  public Optional<InitialAdmissionBindHold> insertIfNoUniqueConflict(
      InitialAdmissionBindHold hold) {
    Result<Record> rows =
        dsl.fetch(
            "INSERT INTO initial_admission_bind_hold (hold_id, hold_fence, tenant_id, realm_uuid, "
                + "playable_state_namespace_uuid, playable_state_scope, game_instance_id, version_id, "
                + "active_lifecycle_epoch, initial_admission_request_id, request_digest, "
                + "expected_no_prior_pointer, expected_catalog_revision, status, diagnostic_expires_at, "
                + "created_at, updated_at, row_version) VALUES (?::uuid, ?::uuid, ?, ?::uuid, "
                + "?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
                + "ON CONFLICT DO NOTHING RETURNING "
                + SELECT_COLUMNS,
            UUID.fromString(hold.holdId()),
            UUID.fromString(hold.holdFence()),
            hold.tenantId(),
            UUID.fromString(hold.realmUuid()),
            UUID.fromString(hold.playableStateNamespaceUuid()),
            hold.playableStateScope(),
            hold.gameInstanceId(),
            hold.versionId(),
            hold.activeLifecycleEpoch(),
            hold.initialAdmissionRequestId(),
            hold.requestDigest(),
            hold.expectedNoPriorPointer(),
            hold.expectedCatalogRevision(),
            hold.status(),
            toLocalDateTime(hold.diagnosticExpiresAt()),
            toLocalDateTime(hold.createdAt()),
            toLocalDateTime(hold.updatedAt()));
    return rows.isEmpty() ? Optional.empty() : Optional.of(toEntity(rows.getFirst()));
  }

  public Optional<InitialAdmissionBindHold> markReconciliationRequired(
      InitialAdmissionBindHold hold, String errorCode, Instant now) {
    int updated =
        dsl.execute(
            "UPDATE initial_admission_bind_hold SET status = 'RECONCILIATION_REQUIRED', "
                + "reconciliation_error = ?, updated_at = ?, row_version = row_version + 1 "
                + "WHERE hold_id = ?::uuid AND hold_fence = ?::uuid AND tenant_id = ? "
                + "AND realm_uuid = ?::uuid AND playable_state_namespace_uuid = ?::uuid "
                + "AND playable_state_scope = ? AND game_instance_id = ? AND version_id = ? "
                + "AND active_lifecycle_epoch = ? AND initial_admission_request_id = ? "
                + "AND request_digest = ? AND expected_no_prior_pointer = ? "
                + "AND expected_catalog_revision = ? AND row_version = ? "
                + "AND status IN ('PENDING', 'RECONCILIATION_REQUIRED')",
            errorCode,
            toLocalDateTime(now),
            UUID.fromString(hold.holdId()),
            UUID.fromString(hold.holdFence()),
            hold.tenantId(),
            UUID.fromString(hold.realmUuid()),
            UUID.fromString(hold.playableStateNamespaceUuid()),
            hold.playableStateScope(),
            hold.gameInstanceId(),
            hold.versionId(),
            hold.activeLifecycleEpoch(),
            hold.initialAdmissionRequestId(),
            hold.requestDigest(),
            hold.expectedNoPriorPointer(),
            hold.expectedCatalogRevision(),
            hold.rowVersion());
    return updated == 1 ? findByHoldIdForUpdate(hold.holdId()) : Optional.empty();
  }

  public Optional<InitialAdmissionBindHold> recordTerminalProof(
      InitialAdmissionBindHold hold,
      String terminalStatus,
      String ownerProofId,
      String ownerProofDigest,
      String pointerAuditId,
      Long pointerVersion,
      Instant now) {
    int updated =
        dsl.execute(
            "UPDATE initial_admission_bind_hold SET status = ?, owner_proof_id = ?, "
                + "owner_proof_digest = ?, owner_pointer_audit_id = ?, owner_pointer_version = ?, "
                + "reconciliation_error = NULL, terminal_at = ?, updated_at = ?, "
                + "row_version = row_version + 1 WHERE hold_id = ?::uuid AND hold_fence = ?::uuid "
                + "AND tenant_id = ? AND realm_uuid = ?::uuid "
                + "AND playable_state_namespace_uuid = ?::uuid AND playable_state_scope = ? "
                + "AND game_instance_id = ? AND version_id = ? AND active_lifecycle_epoch = ? "
                + "AND initial_admission_request_id = ? AND request_digest = ? "
                + "AND expected_no_prior_pointer = ? AND expected_catalog_revision = ? "
                + "AND row_version = ? AND status IN ('PENDING', 'RECONCILIATION_REQUIRED')",
            terminalStatus,
            ownerProofId,
            ownerProofDigest,
            pointerAuditId,
            pointerVersion,
            toLocalDateTime(now),
            toLocalDateTime(now),
            UUID.fromString(hold.holdId()),
            UUID.fromString(hold.holdFence()),
            hold.tenantId(),
            UUID.fromString(hold.realmUuid()),
            UUID.fromString(hold.playableStateNamespaceUuid()),
            hold.playableStateScope(),
            hold.gameInstanceId(),
            hold.versionId(),
            hold.activeLifecycleEpoch(),
            hold.initialAdmissionRequestId(),
            hold.requestDigest(),
            hold.expectedNoPriorPointer(),
            hold.expectedCatalogRevision(),
            hold.rowVersion());
    return updated == 1 ? findByHoldIdForUpdate(hold.holdId()) : Optional.empty();
  }

  private Optional<InitialAdmissionBindHold> one(String query, Object... bindings) {
    Result<Record> rows = dsl.fetch(query, bindings);
    return rows.isEmpty() ? Optional.empty() : Optional.of(toEntity(rows.getFirst()));
  }

  private InitialAdmissionBindHold toEntity(Record record) {
    return new InitialAdmissionBindHold(
        record.get("hold_id", UUID.class).toString(),
        record.get("hold_fence", UUID.class).toString(),
        record.get("tenant_id", Long.class),
        record.get("realm_uuid", UUID.class).toString(),
        record.get("playable_state_namespace_uuid", UUID.class).toString(),
        record.get("playable_state_scope", String.class),
        record.get("game_instance_id", Long.class),
        record.get("version_id", Long.class),
        record.get("active_lifecycle_epoch", Long.class),
        record.get("initial_admission_request_id", String.class),
        record.get("request_digest", String.class),
        record.get("expected_no_prior_pointer", Boolean.class),
        record.get("expected_catalog_revision", Long.class),
        record.get("status", String.class),
        toInstant(record.get("diagnostic_expires_at", LocalDateTime.class)),
        record.get("owner_proof_id", String.class),
        record.get("owner_proof_digest", String.class),
        record.get("owner_pointer_audit_id", String.class),
        record.get("owner_pointer_version", Long.class),
        record.get("reconciliation_error", String.class),
        toInstant(record.get("created_at", LocalDateTime.class)),
        toInstant(record.get("updated_at", LocalDateTime.class)),
        toInstant(record.get("terminal_at", LocalDateTime.class)),
        record.get("row_version", Long.class));
  }

  private LocalDateTime toLocalDateTime(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private Instant toInstant(LocalDateTime localDateTime) {
    return localDateTime == null ? null : localDateTime.toInstant(ZoneOffset.UTC);
  }
}
