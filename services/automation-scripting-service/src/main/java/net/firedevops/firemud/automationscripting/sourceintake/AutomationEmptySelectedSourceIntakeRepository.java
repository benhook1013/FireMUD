package net.firedevops.firemud.automationscripting.sourceintake;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered persistence boundary for immutable, freshly founded empty Automation receipts.
 *
 * <p>The source-family census is deliberately direct: absent associations never prove empty
 * storage. This bounded schema only creates empty-source associations, so any row matching one of
 * them is also a contradiction; rows without such a positive association are unqualified and deny a
 * new founding.
 */
public final class AutomationEmptySelectedSourceIntakeRepository {
  private static final String ASSOCIATION = "automation_empty_selected_source_association";
  private static final String RECEIPT = "automation_empty_selected_source_receipt";
  private static final String RESERVATION = "automation_empty_source_numeric_key_reservation";

  private static final String READ_RECEIPT =
      "SELECT a.target_namespace, a.operation_id, a.fence_id, a.intake_request_id, "
          + "a.canonical_tenant_id, a.canonical_version_id, a.selected_commit_id, "
          + "a.source_revision_id, a.source_revision_order, a.local_tenant_key, "
          + "a.local_version_key, a.request_digest, a.authorization_binding_digest, "
          + "a.receipt_digest, a.associated_at, r.request_digest, r.receipt_digest, "
          + "r.receipt_bytes, r.retained_at FROM "
          + ASSOCIATION
          + " a LEFT JOIN "
          + RECEIPT
          + " r ON r.target_namespace = a.target_namespace "
          + "AND r.intake_request_id = a.intake_request_id "
          + "WHERE a.target_namespace = ? AND a.intake_request_id = ?";

  private final DSLContext dsl;

  public AutomationEmptySelectedSourceIntakeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /** Reads only a committed receipt; absence is not interpreted as abort or terminal settlement. */
  public Optional<AutomationEmptySelectedSourceIntakeReceipt> read(
      String targetNamespace, UUID intakeRequestId) {
    requireNoAmbientOwnerSql("Automation source receipt read");
    requireReadKey(targetNamespace, intakeRequestId);
    return readValidated(dsl, targetNamespace, intakeRequestId);
  }

  /**
   * Reads the original committed receipt for an exact finalized Account authorization. A missing
   * result remains absence; it is never converted into an abort or another terminal value.
   */
  public Optional<AutomationEmptySelectedSourceIntakeReceipt> readCommittedTerminal(
      SelectedOwnerIntakeAuthorizationBinding originalBinding) {
    Objects.requireNonNull(originalBinding, "original Account authorization binding is required");
    if (originalBinding.owner() != Owner.AUTOMATION_SCRIPTING
        || !"account-automation-intake-authorization/v1".equals(originalBinding.schema())
        || !"AUTOMATION_INTAKE_RETENTION".equals(originalBinding.purpose())) {
      throw new IllegalArgumentException("Exact original Automation authorization is required");
    }
    requireNoAmbientOwnerSql("Automation source terminal read");
    requireReadKey(originalBinding.targetNamespace(), originalBinding.intakeRequestId());
    var receipt =
        readValidated(dsl, originalBinding.targetNamespace(), originalBinding.intakeRequestId());
    if (receipt.isPresent()
        && !Arrays.equals(
            originalBinding.canonicalBytes(), receipt.orElseThrow().authorizationBindingBytes())) {
      throw new IntakeConflictException(
          "Automation committed receipt differs from the complete original authorization");
    }
    return receipt;
  }

  /**
   * Persists one fresh empty-source receipt in an independent local transaction, then reads it back
   * after commit using a separate non-transactional owner read.
   */
  public AutomationEmptySelectedSourceIntakeReceipt retainFresh(
      SelectedOwnerEmptySourceInputs inputs, String requestDigest) {
    Objects.requireNonNull(inputs, "validated selected empty-source inputs are required");
    SelectedOwnerIntakeAuthorizationBinding binding = inputs.authorizationBinding();
    String expectedDigest =
        AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
            binding.targetNamespace(),
            binding,
            inputs.worldInventoryReadEvidence().request().freezeEvidence());
    if (!expectedDigest.equals(requestDigest)) {
      throw new IllegalArgumentException("Automation source intake request digest differs");
    }
    if (binding.owner() != Owner.AUTOMATION_SCRIPTING) {
      throw new IllegalArgumentException("Automation owner evidence is required");
    }
    requireNoAmbientOwnerSql("Automation source intake transaction");

    AutomationEmptySelectedSourceIntakeReceipt committed =
        dsl.transactionResult(
            configuration -> {
              DSLContext tx = DSL.using(configuration);
              tx.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
              lockAllocatorAndAssociations(tx);
              lockSourceFamilies(tx);

              // This is the exact request/operation/scope phase of the lock order. A competing
              // local founder has already completed its source census before these rows are read.
              var previous =
                  readValidated(tx, binding.targetNamespace(), binding.intakeRequestId());
              if (previous.isPresent()) {
                AutomationEmptySelectedSourceIntakeReceipt receipt = previous.orElseThrow();
                receipt.requireSameInputs(inputs);
                return receipt;
              }
              requireNoPriorScopeOrOperation(tx, binding);

              Census census = censusAllSourceFamilies(tx);
              census.requireFreshFoundingAllowed();

              long maximumOccupiedKey = maximumOccupiedNumericKey(tx);
              long localTenantKey = Math.addExact(maximumOccupiedKey, 1L);
              long localVersionKey = Math.addExact(maximumOccupiedKey, 2L);
              Census selectedScope =
                  censusSelectedNumericScope(tx, localTenantKey, localVersionKey);
              selectedScope.requireSelectedScopeEmpty();

              reserveCanonicalKey(tx, "TENANT", localTenantKey);
              reserveCanonicalKey(tx, "VERSION", localVersionKey);
              OffsetDateTime retainedAt =
                  requiredScalar(tx, "SELECT CURRENT_TIMESTAMP", OffsetDateTime.class)
                      .withOffsetSameInstant(ZoneOffset.UTC);
              AutomationEmptySelectedSourceIntakeReceipt receipt =
                  AutomationEmptySelectedSourceIntakeReceipt.create(
                      inputs,
                      localTenantKey,
                      localVersionKey,
                      requestDigest,
                      census.scriptsRows(),
                      census.eventBindingRows(),
                      census.patchBaseBindingRows(),
                      census.unqualifiedScriptsRows(),
                      census.unqualifiedEventBindingRows(),
                      census.unqualifiedPatchBaseBindingRows(),
                      census.emptyAssociatedScriptsRows(),
                      census.emptyAssociatedEventBindingRows(),
                      census.emptyAssociatedPatchBaseBindingRows(),
                      selectedScope.scriptsRows(),
                      selectedScope.eventBindingRows(),
                      selectedScope.patchBaseBindingRows(),
                      retainedAt);

              insertAssociation(tx, receipt, retainedAt);
              insertReceipt(tx, receipt, retainedAt);
              return receipt;
            });

    AutomationEmptySelectedSourceIntakeReceipt readBack =
        read(committed.targetNamespace(), committed.intakeRequestId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed Automation source receipt is unavailable"));
    if (!Arrays.equals(committed.canonicalBytes(), readBack.canonicalBytes())
        || !committed.receiptDigest().equals(readBack.receiptDigest())
        || !committed.requestDigest().equals(readBack.requestDigest())) {
      throw new IllegalStateException("Committed Automation source receipt readback differs");
    }
    return readBack;
  }

  private static void lockAllocatorAndAssociations(DSLContext tx) {
    tx.execute(
        "LOCK TABLE "
            + RESERVATION
            + ", "
            + ASSOCIATION
            + ", "
            + RECEIPT
            + " IN SHARE ROW EXCLUSIVE MODE");
  }

  private static void lockSourceFamilies(DSLContext tx) {
    // Fixed order shared with the source-owner allocation protocol.
    tx.execute(
        "LOCK TABLE script_event_bindings, script_patch_base_bindings, scripts IN SHARE MODE");
  }

  private static void requireNoPriorScopeOrOperation(
      DSLContext tx, SelectedOwnerIntakeAuthorizationBinding binding) {
    Record prior =
        tx.fetchOne(
            "SELECT intake_request_id FROM "
                + ASSOCIATION
                + " WHERE target_namespace = ? AND (canonical_tenant_id = ? "
                + "OR canonical_version_id = ? OR operation_id = ?)",
            binding.targetNamespace(),
            binding.tenantId(),
            binding.versionId(),
            binding.operationId());
    if (prior != null) {
      throw new IntakeConflictException(
          "Automation canonical scope or owner operation is already associated");
    }
  }

  private static Census censusAllSourceFamilies(DSLContext tx) {
    long scripts = count(tx, "SELECT COUNT(*) FROM scripts");
    long eventBindings = count(tx, "SELECT COUNT(*) FROM script_event_bindings");
    long patchBaseBindings = count(tx, "SELECT COUNT(*) FROM script_patch_base_bindings");

    long unqualifiedScripts =
        count(
            tx,
            "SELECT COUNT(*) FROM scripts s WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = s.tenant_id "
                + "AND a.local_version_key = s.base_version_id)");
    long unqualifiedEventBindings =
        count(
            tx,
            "SELECT COUNT(*) FROM script_event_bindings b WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = b.tenant_id "
                + "AND a.local_version_key = b.base_version_id)");
    long unqualifiedPatchBaseBindings =
        count(
            tx,
            "SELECT COUNT(*) FROM script_patch_base_bindings b WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = "
                + "automation_source_positive_numeric_key(b.tenant_id) "
                + "AND a.local_version_key = b.base_version_id)");

    return new Census(
        scripts,
        eventBindings,
        patchBaseBindings,
        unqualifiedScripts,
        unqualifiedEventBindings,
        unqualifiedPatchBaseBindings,
        scripts - unqualifiedScripts,
        eventBindings - unqualifiedEventBindings,
        patchBaseBindings - unqualifiedPatchBaseBindings);
  }

  private static Census censusSelectedNumericScope(DSLContext tx, long tenantKey, long versionKey) {
    long scripts =
        count(
            tx,
            "SELECT COUNT(*) FROM scripts WHERE tenant_id = ? OR base_version_id = ?",
            tenantKey,
            versionKey);
    long eventBindings =
        count(
            tx,
            "SELECT COUNT(*) FROM script_event_bindings WHERE tenant_id = ? "
                + "OR base_version_id = ?",
            tenantKey,
            versionKey);
    long patchBaseBindings =
        count(
            tx,
            "SELECT COUNT(*) FROM script_patch_base_bindings WHERE "
                + "automation_source_positive_numeric_key(tenant_id) = ? "
                + "OR base_version_id = ?",
            tenantKey,
            versionKey);
    return new Census(scripts, eventBindings, patchBaseBindings, 0L, 0L, 0L, 0L, 0L, 0L);
  }

  private static long maximumOccupiedNumericKey(DSLContext tx) {
    return requiredScalar(
        tx,
        "SELECT COALESCE(MAX(occupied_key), 0) FROM ("
            + "SELECT numeric_key AS occupied_key FROM "
            + RESERVATION
            + " UNION ALL SELECT tenant_id FROM scripts"
            + " UNION ALL SELECT base_version_id FROM scripts WHERE base_version_id IS NOT NULL"
            + " UNION ALL SELECT tenant_id FROM script_event_bindings"
            + " UNION ALL SELECT base_version_id FROM script_event_bindings "
            + "WHERE base_version_id IS NOT NULL"
            + " UNION ALL SELECT automation_source_positive_numeric_key(tenant_id) "
            + "FROM script_patch_base_bindings"
            + " UNION ALL SELECT base_version_id FROM script_patch_base_bindings"
            + ") AS occupied",
        Long.class);
  }

  private static void reserveCanonicalKey(DSLContext tx, String kind, long key) {
    int inserted =
        tx.execute(
            "INSERT INTO "
                + RESERVATION
                + " (key_kind, numeric_key, claim_kind) VALUES (?, ?, 'CANONICAL_EMPTY_SOURCE')",
            kind,
            key);
    if (inserted != 1) {
      throw new IntakeConflictException("Automation source numeric key was not reserved");
    }
  }

  private static void insertAssociation(
      DSLContext tx,
      AutomationEmptySelectedSourceIntakeReceipt receipt,
      OffsetDateTime retainedAt) {
    int inserted =
        tx.execute(
            "INSERT INTO "
                + ASSOCIATION
                + " (target_namespace, operation_id, fence_id, intake_request_id, "
                + "canonical_tenant_id, canonical_version_id, selected_commit_id, "
                + "source_revision_id, source_revision_order, local_tenant_key, "
                + "local_version_key, request_digest, authorization_binding_digest, "
                + "receipt_digest, associated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            receipt.targetNamespace(),
            receipt.operationId(),
            receipt.fenceId(),
            receipt.intakeRequestId(),
            receipt.canonicalTenantId(),
            receipt.canonicalVersionId(),
            receipt.selectedCommitId(),
            receipt.sourceRevisionId(),
            receipt.sourceRevisionOrder(),
            receipt.localTenantKey(),
            receipt.localVersionKey(),
            receipt.requestDigest(),
            receipt.authorizationBindingDigest(),
            receipt.receiptDigest(),
            retainedAt);
    if (inserted != 1) {
      throw new IntakeConflictException("Automation source scope association was not inserted");
    }
  }

  private static void insertReceipt(
      DSLContext tx,
      AutomationEmptySelectedSourceIntakeReceipt receipt,
      OffsetDateTime retainedAt) {
    int inserted =
        tx.execute(
            "INSERT INTO "
                + RECEIPT
                + " (target_namespace, intake_request_id, request_digest, receipt_digest, "
                + "receipt_bytes, retained_at) VALUES (?, ?, ?, ?, ?, ?)",
            receipt.targetNamespace(),
            receipt.intakeRequestId(),
            receipt.requestDigest(),
            receipt.receiptDigest(),
            receipt.canonicalBytes(),
            retainedAt);
    if (inserted != 1) {
      throw new IntakeConflictException("Automation source receipt was not inserted");
    }
  }

  private static Optional<AutomationEmptySelectedSourceIntakeReceipt> readValidated(
      DSLContext context, String namespace, UUID requestId) {
    Record record = context.fetchOne(READ_RECEIPT, namespace, requestId);
    if (record == null) {
      return Optional.empty();
    }
    byte[] receiptBytes = record.get(17, byte[].class);
    if (receiptBytes == null) {
      throw new IllegalStateException("Automation source association has no retained receipt");
    }
    AutomationEmptySelectedSourceIntakeReceipt receipt =
        AutomationEmptySelectedSourceIntakeReceipt.fromStored(receiptBytes);
    if (!Objects.equals(record.get(0, String.class), receipt.targetNamespace())
        || !Objects.equals(record.get(1, UUID.class), receipt.operationId())
        || !Objects.equals(record.get(2, UUID.class), receipt.fenceId())
        || !Objects.equals(record.get(3, UUID.class), receipt.intakeRequestId())
        || !Objects.equals(record.get(4, UUID.class), receipt.canonicalTenantId())
        || !Objects.equals(record.get(5, UUID.class), receipt.canonicalVersionId())
        || !Objects.equals(record.get(6, UUID.class), receipt.selectedCommitId())
        || !Objects.equals(record.get(7, UUID.class), receipt.sourceRevisionId())
        || !Objects.equals(record.get(8, String.class), receipt.sourceRevisionOrder())
        || !Objects.equals(record.get(9, Long.class), receipt.localTenantKey())
        || !Objects.equals(record.get(10, Long.class), receipt.localVersionKey())
        || !Objects.equals(record.get(11, String.class), receipt.requestDigest())
        || !Objects.equals(record.get(12, String.class), receipt.authorizationBindingDigest())
        || !Objects.equals(record.get(13, String.class), receipt.receiptDigest())
        || !Objects.equals(record.get(15, String.class), receipt.requestDigest())
        || !Objects.equals(record.get(16, String.class), receipt.receiptDigest())
        || !Objects.equals(
            record.get(18, OffsetDateTime.class).toInstant(), receipt.retainedAt().toInstant())
        || !Objects.equals(
            record.get(14, OffsetDateTime.class).toInstant(), receipt.retainedAt().toInstant())
        || !Arrays.equals(receipt.canonicalBytes(), receiptBytes)) {
      throw new IllegalStateException(
          "Automation source receipt differs from its owner association");
    }
    return Optional.of(receipt);
  }

  private static long count(DSLContext context, String sql, Object... parameters) {
    long value = requiredScalar(context, sql, Long.class, parameters);
    if (value < 0L) {
      throw new IllegalStateException("Automation source census returned an invalid count");
    }
    return value;
  }

  private static <T> T requiredScalar(
      DSLContext context, String sql, Class<T> valueType, Object... parameters) {
    Record record = context.fetchOne(sql, parameters);
    if (record == null) {
      throw new IllegalStateException("Automation owner query returned no row");
    }
    T value = record.get(0, valueType);
    if (value == null) {
      throw new IllegalStateException("Automation owner query returned a null value");
    }
    return value;
  }

  private static void requireReadKey(String namespace, UUID requestId) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace) || requestId == null) {
      throw new IllegalArgumentException("Automation source receipt lookup identity is required");
    }
    DraftAuthorizationFenceBinding.requireUuid(requestId);
  }

  private static void requireNoAmbientOwnerSql(String action) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(action + " requires no ambient owner SQL");
    }
  }

  private record Census(
      long scriptsRows,
      long eventBindingRows,
      long patchBaseBindingRows,
      long unqualifiedScriptsRows,
      long unqualifiedEventBindingRows,
      long unqualifiedPatchBaseBindingRows,
      long emptyAssociatedScriptsRows,
      long emptyAssociatedEventBindingRows,
      long emptyAssociatedPatchBaseBindingRows) {
    private void requireFreshFoundingAllowed() {
      if (unqualifiedScriptsRows != 0L
          || unqualifiedEventBindingRows != 0L
          || unqualifiedPatchBaseBindingRows != 0L) {
        throw new UnqualifiedSourceRowsException(
            "Fresh Automation founding requires a positively owned empty source inventory");
      }
      if (emptyAssociatedScriptsRows != 0L
          || emptyAssociatedEventBindingRows != 0L
          || emptyAssociatedPatchBaseBindingRows != 0L) {
        throw new IntakeConflictException(
            "An existing empty Automation association contains authored source rows");
      }
    }

    private void requireSelectedScopeEmpty() {
      if (scriptsRows != 0L || eventBindingRows != 0L || patchBaseBindingRows != 0L) {
        throw new IntakeConflictException(
            "Allocated Automation source scope is not empty across all source families");
      }
    }
  }

  public static class IntakeConflictException extends IllegalStateException {
    public IntakeConflictException(String message) {
      super(message);
    }
  }

  public static final class UnqualifiedSourceRowsException extends IntakeConflictException {
    public UnqualifiedSourceRowsException(String message) {
      super(message);
    }
  }
}
