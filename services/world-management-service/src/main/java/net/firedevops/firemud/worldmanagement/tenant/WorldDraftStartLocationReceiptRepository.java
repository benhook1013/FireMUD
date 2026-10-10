package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Stores and independently checks one immutable original Account-bound start selector. */
final class WorldDraftStartLocationReceiptRepository {
  private final DSLContext dsl;

  WorldDraftStartLocationReceiptRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  void store(WorldDraftStartLocationReceipt receipt, byte[] accountBindingBytes) {
    Objects.requireNonNull(receipt, "receipt");
    Objects.requireNonNull(accountBindingBytes, "accountBindingBytes");
    dsl.execute(
        "INSERT INTO world_draft_start_location_receipt (operation_id,request_id,commit_id,"
            + "authorization_fence_id,target_namespace,account_binding_bytes,account_binding_digest,binding_digest,"
            + "canonical_tenant_id,canonical_version_id,room_template_id,graph_digest,receipt_digest,receipt_bytes) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        receipt.operationId(),
        receipt.requestId(),
        receipt.commitId(),
        receipt.authorizationFenceId(),
        receipt.targetNamespace(),
        accountBindingBytes,
        receipt.accountBindingDigest(),
        receipt.bindingDigest(),
        receipt.startLocation().tenantId(),
        receipt.startLocation().versionId(),
        receipt.startLocation().roomTemplateId(),
        receipt.graphDigest(),
        receipt.receiptDigest(),
        receipt.canonicalBytes());
  }

  Optional<WorldDraftStartLocationReceipt> read(
      WorldDraftGraphApplication application, byte[] graphBytes) {
    var operation = application.operation();
    List<Record> rows =
        dsl.fetch(
            "SELECT * FROM world_draft_start_location_receipt WHERE operation_id=? OR request_id=? "
                + "OR commit_id=? OR authorization_fence_id=?",
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() != 1 || application.plan().graph().freshGraphDeclaration().isEmpty()) {
      throw new ConflictException(
          "World start-location receipt has changed or lacks its original declaration");
    }
    WorldDraftStartLocationReceipt expected =
        WorldDraftStartLocationReceipt.create(application, graphBytes);
    Record row = rows.getFirst();
    if (!expected.operationId().equals(row.get("operation_id", java.util.UUID.class))
        || !expected.requestId().equals(row.get("request_id", java.util.UUID.class))
        || !expected.commitId().equals(row.get("commit_id", java.util.UUID.class))
        || !expected
            .authorizationFenceId()
            .equals(row.get("authorization_fence_id", java.util.UUID.class))
        || !expected.targetNamespace().equals(row.get("target_namespace", String.class))
        || !Arrays.equals(
            operation.accountBindingBytes(), row.get("account_binding_bytes", byte[].class))
        || !expected.accountBindingDigest().equals(row.get("account_binding_digest", String.class))
        || !expected.bindingDigest().equals(row.get("binding_digest", String.class))
        || !expected
            .startLocation()
            .tenantId()
            .equals(row.get("canonical_tenant_id", java.util.UUID.class))
        || !expected
            .startLocation()
            .versionId()
            .equals(row.get("canonical_version_id", java.util.UUID.class))
        || !expected
            .startLocation()
            .roomTemplateId()
            .equals(row.get("room_template_id", java.util.UUID.class))
        || !expected.graphDigest().equals(row.get("graph_digest", String.class))
        || !expected.receiptDigest().equals(row.get("receipt_digest", String.class))
        || !Arrays.equals(expected.canonicalBytes(), row.get("receipt_bytes", byte[].class))) {
      throw new ConflictException(
          "World typed start-location receipt differs from exact original request, commit, Account binding or graph");
    }
    return Optional.of(expected);
  }
}
