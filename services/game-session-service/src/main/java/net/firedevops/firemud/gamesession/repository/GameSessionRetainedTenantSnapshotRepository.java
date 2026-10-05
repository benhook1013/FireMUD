package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captures all retained Game Session tenant identity rows behind a short database write fence. */
@Repository
public class GameSessionRetainedTenantSnapshotRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String LOCK_SOURCE_FAMILIES =
      "LOCK TABLE game_instances, gameplay_admission_pointer, "
          + "gameplay_tenant_shared_playable_state_namespace, "
          + "gameplay_admission_pointer_identity_backfill_issue, "
          + "gameplay_admission_pointer_event, prepared_version_upgrade "
          + "IN SHARE MODE NOWAIT";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted Spring collaborator is validated before use; no resources or finalizer are acquired."
              + " The repository remains non-final for transaction proxying.")
  public GameSessionRetainedTenantSnapshotRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /**
   * Captures the retained owner projection under a caller-owned READ COMMITTED transaction.
   *
   * <p>The one-time SHARE fence conflicts with ordinary writes on each included relation and is
   * retained until the caller's transaction commits or rolls back. Callers must not perform a
   * network request while this owner transaction remains open.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public GameSessionRetainedTenantSnapshot capture(
      String targetNamespace, String legacyGameSessionTenantId) {
    GameSessionRetainedTenantSnapshot.validateIdentity(targetNamespace, legacyGameSessionTenantId);
    requireOwnerTransaction();

    long tenantId = Long.parseLong(legacyGameSessionTenantId);
    dsl.execute(LOCK_SOURCE_FAMILIES);
    if (hasBackfillIssue(tenantId)) {
      throw new InvalidRetainedTenantSnapshotException(
          "Game Session retained tenant has an unresolved admission-pointer identity backfill issue");
    }

    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("schemaVersion", 1);
    envelope.put("targetNamespace", targetNamespace);
    envelope.put("legacyGameSessionTenantId", legacyGameSessionTenantId);
    envelope.set("instances", readInstances(tenantId));
    envelope.set("pointers", readPointers(tenantId));
    envelope.set("sharedNamespaces", readSharedNamespaces(tenantId));
    envelope.set("backfillIssues", JSON.createArrayNode());
    envelope.set("pointerEvents", readPointerEvents(tenantId));
    envelope.set("preparedUpgrades", readPreparedUpgrades(tenantId));
    return GameSessionRetainedTenantSnapshot.fromProjection(
        targetNamespace, legacyGameSessionTenantId, envelope);
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Retained-tenant snapshot requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Retained-tenant snapshot requires a read-write owner transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                "Retained-tenant snapshot requires the DSL connection to join the owner transaction");
          }
          if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Retained-tenant snapshot requires READ COMMITTED isolation");
          }
          return null;
        });
    Record isolationRow = dsl.fetchOne("SHOW transaction_isolation");
    String isolation = isolationRow == null ? null : isolationRow.get(0, String.class);
    if (isolation == null || !"read committed".equalsIgnoreCase(isolation.trim())) {
      throw new IllegalStateException("Retained-tenant snapshot requires READ COMMITTED isolation");
    }
    Record readOnlyRow = dsl.fetchOne("SHOW transaction_read_only");
    String readOnly = readOnlyRow == null ? null : readOnlyRow.get(0, String.class);
    if (readOnly == null || !"off".equalsIgnoreCase(readOnly.trim())) {
      throw new IllegalStateException(
          "Retained-tenant snapshot requires a read-write owner transaction");
    }
  }

  private boolean hasBackfillIssue(long tenantId) {
    return !dsl.fetch(
            "SELECT 1 FROM gameplay_admission_pointer_identity_backfill_issue "
                + "WHERE tenant_id = ? LIMIT 1",
            tenantId)
        .isEmpty();
  }

  private ArrayNode readInstances(long tenantId) {
    return readRows(
        "SELECT id, tenant_id, runtime_version, script_patch_version, owner_account_id, status, "
            + "row_version, game_template_id, launch_descriptor_id, version_id, release_bundle_id, "
            + "version_state_epoch, generation_config_revision, remap_set_id, "
            + "script_patch_pinned_control_plane_request_id, script_pin_epoch "
            + "FROM game_instances WHERE tenant_id = ? ORDER BY id ASC",
        tenantId,
        record -> {
          ObjectNode row = JSON.createObjectNode();
          putLong(row, record, "id");
          putLong(row, record, "tenant_id");
          putText(row, record, "runtime_version");
          putNullableText(row, record, "script_patch_version");
          putLong(row, record, "owner_account_id");
          putText(row, record, "status");
          putLong(row, record, "row_version");
          putNullableLong(row, record, "game_template_id");
          putNullableText(row, record, "launch_descriptor_id");
          putNullableLong(row, record, "version_id");
          putNullableLong(row, record, "release_bundle_id");
          putNullableLong(row, record, "version_state_epoch");
          putNullableText(row, record, "generation_config_revision");
          putNullableText(row, record, "remap_set_id");
          putNullableText(row, record, "script_patch_pinned_control_plane_request_id");
          putNullableLong(row, record, "script_pin_epoch");
          return row;
        });
  }

  private ArrayNode readPointers(long tenantId) {
    return readRows(
        "SELECT id, tenant_id, game_instance_id, world_slug, realm_slug, pointer_version, "
            + "catalog_revision, visible, requires_character_selection, state_scope, "
            + "character_creation_policy, public_production_realm, realm_id, "
            + "playable_state_namespace_id FROM gameplay_admission_pointer "
            + "WHERE tenant_id = ? ORDER BY id ASC",
        tenantId,
        record -> {
          ObjectNode row = JSON.createObjectNode();
          putLong(row, record, "id");
          putLong(row, record, "tenant_id");
          putLong(row, record, "game_instance_id");
          putText(row, record, "world_slug");
          putText(row, record, "realm_slug");
          putLong(row, record, "pointer_version");
          putLong(row, record, "catalog_revision");
          putBoolean(row, record, "visible");
          putBoolean(row, record, "requires_character_selection");
          putText(row, record, "state_scope");
          putText(row, record, "character_creation_policy");
          putBoolean(row, record, "public_production_realm");
          putUuid(row, record, "realm_id");
          putUuid(row, record, "playable_state_namespace_id");
          return row;
        });
  }

  private ArrayNode readSharedNamespaces(long tenantId) {
    return readRows(
        "SELECT tenant_id, playable_state_namespace_id "
            + "FROM gameplay_tenant_shared_playable_state_namespace "
            + "WHERE tenant_id = ? ORDER BY tenant_id ASC",
        tenantId,
        record -> {
          ObjectNode row = JSON.createObjectNode();
          putLong(row, record, "tenant_id");
          putUuid(row, record, "playable_state_namespace_id");
          return row;
        });
  }

  private ArrayNode readPointerEvents(long tenantId) {
    return readRows(
        "SELECT id, tenant_id, game_instance_id, world_slug, realm_slug, pointer_version, visible, "
            + "requires_character_selection, state_scope, character_creation_policy, "
            + "control_plane_request_id, prepared_version_upgrade_id, public_production_realm "
            + "FROM gameplay_admission_pointer_event WHERE tenant_id = ? ORDER BY id ASC",
        tenantId,
        record -> {
          ObjectNode row = JSON.createObjectNode();
          putLong(row, record, "id");
          putLong(row, record, "tenant_id");
          putLong(row, record, "game_instance_id");
          putText(row, record, "world_slug");
          putText(row, record, "realm_slug");
          putLong(row, record, "pointer_version");
          putBoolean(row, record, "visible");
          putBoolean(row, record, "requires_character_selection");
          putText(row, record, "state_scope");
          putText(row, record, "character_creation_policy");
          putText(row, record, "control_plane_request_id");
          putNullableText(row, record, "prepared_version_upgrade_id");
          putBoolean(row, record, "public_production_realm");
          return row;
        });
  }

  private ArrayNode readPreparedUpgrades(long tenantId) {
    return readRows(
        "SELECT id, tenant_id, preparation_id, control_plane_request_id, source_game_instance_id, "
            + "source_version_id, target_version_id, target_launch_descriptor_id, remap_set_id, "
            + "result, executed_target_game_instance_id, executed_pointer_version, "
            + "execution_control_plane_request_id FROM prepared_version_upgrade "
            + "WHERE tenant_id = ? ORDER BY id ASC",
        tenantId,
        record -> {
          ObjectNode row = JSON.createObjectNode();
          putLong(row, record, "id");
          putLong(row, record, "tenant_id");
          putText(row, record, "preparation_id");
          putText(row, record, "control_plane_request_id");
          putLong(row, record, "source_game_instance_id");
          putLong(row, record, "source_version_id");
          putLong(row, record, "target_version_id");
          putText(row, record, "target_launch_descriptor_id");
          putNullableText(row, record, "remap_set_id");
          putText(row, record, "result");
          putNullableLong(row, record, "executed_target_game_instance_id");
          putNullableLong(row, record, "executed_pointer_version");
          putNullableText(row, record, "execution_control_plane_request_id");
          return row;
        });
  }

  private ArrayNode readRows(String query, long tenantId, Function<Record, ObjectNode> mapper) {
    ArrayNode rows = JSON.createArrayNode();
    Result<Record> records = dsl.fetch(query, tenantId);
    for (Record record : records) {
      rows.add(mapper.apply(record));
    }
    return rows;
  }

  private static void putLong(ObjectNode node, Record record, String field) {
    Long value = record.get(field, Long.class);
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, Long.toString(value));
    }
  }

  private static void putNullableLong(ObjectNode node, Record record, String field) {
    Long value = record.get(field, Long.class);
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, Long.toString(value));
    }
  }

  private static void putText(ObjectNode node, Record record, String field) {
    String value = record.get(field, String.class);
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static void putNullableText(ObjectNode node, Record record, String field) {
    putText(node, record, field);
  }

  private static void putBoolean(ObjectNode node, Record record, String field) {
    Boolean value = record.get(field, Boolean.class);
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static void putUuid(ObjectNode node, Record record, String field) {
    UUID value = record.get(field, UUID.class);
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.toString());
    }
  }

  /** Fail-closed owner evidence error for malformed or unresolved retained rows. */
  public static final class InvalidRetainedTenantSnapshotException extends IllegalStateException {
    public InvalidRetainedTenantSnapshotException(String message) {
      super(message);
    }
  }
}
