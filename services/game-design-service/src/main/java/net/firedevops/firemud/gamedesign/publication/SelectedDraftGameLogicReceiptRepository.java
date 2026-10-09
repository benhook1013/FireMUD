package net.firedevops.firemud.gamedesign.publication;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered immutable derived-receipt storage, separate from publication-operation bytes. */
public class SelectedDraftGameLogicReceiptRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = {"CT_CONSTRUCTOR_THROW", "EI_EXPOSE_REP2"},
      justification =
          "Owner-local persistence collaborator; no resources acquired or finalizer used.")
  public SelectedDraftGameLogicReceiptRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public Optional<SelectedDraftGameLogicReceipt> read(
      AuthoredDraftPublishSelection selection, GameLogicIntakeAuthorizationBinding authorization) {
    SelectedDraftGameLogicReceipt.requireExactSelection(selection, authorization);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_design_selected_game_logic_receipt WHERE canonical_tenant_id = ? AND publish_request_id = ?",
            selection.intent().canonicalTenantId(),
            selection.intent().publishRequestId());
    if (row == null) return Optional.empty();
    var retained = decode(row);
    retained.requireExactRequest(selection, authorization);
    return Optional.of(retained);
  }

  public SelectedDraftGameLogicReceipt retain(SelectedDraftGameLogicReceipt candidate) {
    requireTransaction();
    var selection = candidate.selection();
    // Keep the existing owner lock order: Version before immutable selection.
    var target = selection.target();
    if (dsl.fetchOne(
            "SELECT v.id FROM version v JOIN game g "
                + "ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key "
                + "AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "AND g.tenant_identity_source_game_id = g.id "
                + "AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id "
                + "WHERE v.id = ? AND v.tenant_id = ? AND v.canonical_tenant_id = ? "
                + "AND v.canonical_version_id = ? AND v.identity_source_game_row_id = ? "
                + "AND v.identity_source_game_tenant_key = ? AND v.identity_source_provenance_kind = ? "
                + "AND v.identity_source_game_row_id > 0 "
                + "AND v.identity_source_game_tenant_key = v.tenant_id "
                + "AND v.identity_source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29') "
                + "AND v.version_state_epoch > 0 FOR UPDATE OF v",
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind())
        == null) {
      throw new IllegalStateException("Selected Version source proof unavailable");
    }
    dsl.fetchOne(
        "SELECT canonical_tenant_id FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE",
        selection.target().canonicalTenantId(),
        selection.target().canonicalVersionId());
    var retainedSelection =
        new AuthoredDraftPublishSelectionRepository(dsl, new DraftCommitCoordinatorRepository(dsl))
            .read(
                selection.intent().canonicalTenantId(),
                selection.intent().canonicalVersionId(),
                selection.intent().publishRequestId())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_SELECTION_UNAVAILABLE"))
            .selection();
    if (!Arrays.equals(selection.canonicalBytes(), retainedSelection.canonicalBytes())) {
      throw new IllegalStateException("Selected publication changed before receipt retention");
    }
    var prior = read(selection, candidate.authorization());
    if (prior.isPresent()) return requireSameReceipt(candidate, prior.get());
    dsl.execute(
        "INSERT INTO game_design_selected_game_logic_receipt (canonical_tenant_id, canonical_version_id, publish_request_id, selection_digest, workflow_identity, selection_bytes, authorization_bytes, authorization_digest, receipt_bytes, receipt_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (canonical_tenant_id, publish_request_id) DO NOTHING",
        selection.intent().canonicalTenantId(),
        selection.intent().canonicalVersionId(),
        selection.intent().publishRequestId(),
        selection.digest(),
        candidate.workflowIdentity(),
        selection.canonicalBytes(),
        candidate.authorization().canonicalBytes(),
        candidate.authorization().digest(),
        candidate.receipt().canonicalBytes(),
        candidate.receipt().digest());
    return requireSameReceipt(candidate, read(selection, candidate.authorization()).orElseThrow());
  }

  private static SelectedDraftGameLogicReceipt requireSameReceipt(
      SelectedDraftGameLogicReceipt candidate, SelectedDraftGameLogicReceipt retained) {
    if (!Arrays.equals(candidate.receipt().canonicalBytes(), retained.receipt().canonicalBytes())) {
      throw new IllegalStateException("Immutable selected Game Logic receipt conflict");
    }
    return retained;
  }

  private static SelectedDraftGameLogicReceipt decode(Record row) {
    byte[] selectionBytes = row.get("selection_bytes", byte[].class);
    var selection =
        AuthoredDraftPublishSelection.fromStored(
            new String(selectionBytes, StandardCharsets.UTF_8),
            row.get("selection_digest", String.class));
    var authorization =
        GameLogicIntakeAuthorizationBinding.fromStored(
            row.get("authorization_bytes", byte[].class));
    var receipt =
        AccountGameLogicIntakeSettlementEvidence.fromStored(row.get("receipt_bytes", byte[].class));
    var value = new SelectedDraftGameLogicReceipt(selection, authorization, receipt);
    if (!Arrays.equals(selectionBytes, selection.canonicalBytes())
        || !selection
            .intent()
            .canonicalTenantId()
            .equals(row.get("canonical_tenant_id", java.util.UUID.class))
        || !selection
            .intent()
            .canonicalVersionId()
            .equals(row.get("canonical_version_id", java.util.UUID.class))
        || !selection
            .intent()
            .publishRequestId()
            .equals(row.get("publish_request_id", String.class))
        || !value.workflowIdentity().equals(row.get("workflow_identity", String.class))
        || !authorization.digest().equals(row.get("authorization_digest", String.class))
        || !receipt.digest().equals(row.get("receipt_digest", String.class))) {
      throw new IllegalStateException("Stored selected Game Logic receipt integrity conflict");
    }
    return value;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Receipt retention requires writable READ_COMMITTED transaction");
    }
  }
}
