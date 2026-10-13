package net.firedevops.firemud.gamedesign.draft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.DraftCommitStateConflictException;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.LockedVersion;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.PublicationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retains the first exact authored-Draft selection for a full-version publication request. The
 * durable row is a reservation; this repository does not release it or infer any World freeze or
 * terminal participant outcome. Later workflow code must recover the same selection and persist
 * only definitive authenticated owner readback in its separately owned boundary.
 */
@Repository
public class AuthoredDraftPublishSelectionRepository {
  private static final String SELECTION_TABLE = "game_design_authored_draft_publish_selection";
  private final DSLContext dsl;
  private final DraftCommitCoordinatorRepository coordinator;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Internal collaborators only; no resources are acquired and no finalizer is used.")
  public AuthoredDraftPublishSelectionRepository(
      DSLContext dsl, DraftCommitCoordinatorRepository coordinator) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  /**
   * Selects exactly one caller-named current synchronized commit for an existing full Draft
   * Version. Exact request replay returns the immutable first selection before reading mutable
   * Version state or current-fence state again.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public SelectionSnapshot reserve(PublishIntent intent) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(intent, "intent");

    Optional<SelectionSnapshot> prior =
        readByPublishRequest(intent.canonicalTenantId(), intent.publishRequestId());
    if (prior.isPresent()) {
      requireExactIntent(intent, prior.get().selection().intent());
      return prior.get();
    }

    LockedVersion version =
        coordinator.lockVersionTarget(intent.canonicalTenantId(), intent.canonicalVersionId());
    SelectionSnapshot existing = findByTarget(version.target(), false);
    if (existing != null) {
      requireExactIntent(intent, existing.selection().intent());
      return existing;
    }

    requireEligibleFirstSelection(version, intent);
    if (coordinator.readApplicationSlot(version.target()).isPresent()) {
      throw new DraftCommitStateConflictException(
          "An active or unresolved owner application prevents Draft publication selection");
    }
    PublicationEvidence evidence =
        coordinator.requireSynchronizedPublicationEvidence(
            version.target(),
            intent.selectedCommitRequestId(),
            intent.selectedCommitId(),
            intent.selectedCommitDigest());
    AuthoredDraftPublishSelection selection =
        AuthoredDraftPublishSelection.capture(intent, version.target(), evidence);

    int inserted;
    try {
      inserted = insert(selection);
    } catch (DataAccessException exception) {
      if (isUniqueConstraintViolation(exception)) {
        throw new DraftCommitIdentityConflictException(
            "An authored Draft publication operation already reserves an exact Version", exception);
      }
      throw exception;
    }
    SelectionSnapshot persisted = findByTarget(version.target(), false);
    if (persisted == null) {
      throw new IllegalStateException(
          "Durable authored Draft publication selection disappeared after insert");
    }
    requireExactIntent(intent, persisted.selection().intent());
    if (inserted == 0 && !selection.equals(persisted.selection())) {
      throw new DraftCommitIdentityConflictException(
          "An authored Draft publish request was reused with changed exact binding data");
    }
    return persisted;
  }

  /** Reads the immutable selection only when all canonical target and request fields match. */
  public Optional<SelectionSnapshot> readByPublishRequest(
      UUID canonicalTenantId, String publishRequestId) {
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    net.firedevops.firemud.common.publication.PublicationDigestRequestBinding
        .validatePublicationIdentity(canonicalTenantId.toString(), publishRequestId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + SELECTION_TABLE
                + " WHERE canonical_tenant_id = ? AND publish_request_id = ?",
            canonicalTenantId,
            publishRequestId);
    return row == null ? Optional.empty() : Optional.of(toSnapshot(row));
  }

  /** Reads the immutable selection only when all canonical target and request fields match. */
  public Optional<SelectionSnapshot> read(
      UUID canonicalTenantId, UUID canonicalVersionId, String publishRequestId) {
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    net.firedevops.firemud.common.publication.PublicationDigestRequestBinding
        .validatePublicationIdentity(canonicalTenantId.toString(), publishRequestId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + SELECTION_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            canonicalTenantId,
            canonicalVersionId);
    if (row == null || !publishRequestId.equals(row.get("publish_request_id", String.class))) {
      return Optional.empty();
    }
    return Optional.of(toSnapshot(row));
  }

  private int insert(AuthoredDraftPublishSelection selection) {
    TargetProof target = selection.target();
    PublishIntent intent = selection.intent();
    return dsl.execute(
        "INSERT INTO "
            + SELECTION_TABLE
            + " (canonical_tenant_id, canonical_version_id, game_design_version_row_id, "
            + "game_design_version_tenant_key, source_game_row_id, source_game_tenant_key, "
            + "source_provenance_kind, publish_request_id, version_state_epoch, "
            + "selected_commit_request_id, selected_commit_id, selected_commit_digest, "
            + "selection_digest, selection_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (canonical_tenant_id, canonical_version_id) DO NOTHING",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        target.gameDesignVersionTenantKey(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.sourceProvenanceKind(),
        intent.publishRequestId(),
        Long.parseLong(intent.expectedVersionStateEpoch()),
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest(),
        selection.digest(),
        selection.canonicalJson());
  }

  private SelectionSnapshot findByTarget(TargetProof target, boolean forUpdate) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + SELECTION_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            target.canonicalTenantId(),
            target.canonicalVersionId());
    return row == null ? null : toSnapshot(row);
  }

  private SelectionSnapshot toSnapshot(Record row) {
    AuthoredDraftPublishSelection selection =
        AuthoredDraftPublishSelection.fromStored(
            row.get("selection_json", String.class), row.get("selection_digest", String.class));
    TargetProof target = selection.target();
    PublishIntent intent = selection.intent();
    if (!row.get("canonical_tenant_id", UUID.class).equals(target.canonicalTenantId())
        || !row.get("canonical_version_id", UUID.class).equals(target.canonicalVersionId())
        || !row.get("game_design_version_row_id", Long.class)
            .equals(target.gameDesignVersionRowId())
        || !row.get("game_design_version_tenant_key", String.class)
            .equals(target.gameDesignVersionTenantKey())
        || !row.get("source_game_row_id", Long.class).equals(target.sourceGameRowId())
        || !row.get("source_game_tenant_key", String.class).equals(target.sourceGameTenantKey())
        || !row.get("source_provenance_kind", String.class).equals(target.sourceProvenanceKind())
        || !row.get("publish_request_id", String.class).equals(intent.publishRequestId())
        || !Long.toString(row.get("version_state_epoch", Long.class))
            .equals(intent.expectedVersionStateEpoch())
        || !row.get("selected_commit_request_id", UUID.class)
            .equals(intent.selectedCommitRequestId())
        || !row.get("selected_commit_id", UUID.class).equals(intent.selectedCommitId())
        || !row.get("selected_commit_digest", String.class).equals(intent.selectedCommitDigest())) {
      throw new IllegalStateException(
          "Authored Draft publication selection indexed identity differs from exact stored binding");
    }
    return new SelectionSnapshot(selection, row.get("created_at", OffsetDateTime.class));
  }

  private static void requireEligibleFirstSelection(LockedVersion version, PublishIntent intent) {
    if (!"DRAFT".equals(version.versionState())
        || version.scriptOnly()
        || version.scriptPatchVersion() != null
        || version.baseVersionId() != null) {
      throw new DraftCommitStateConflictException(
          "Only an existing non-script-only full Version in DRAFT can be selected for publication");
    }
    if (!Long.toString(version.versionStateEpoch()).equals(intent.expectedVersionStateEpoch())) {
      throw new DraftCommitStateConflictException(
          "Authored Draft publication selection has a stale Version state epoch");
    }
  }

  private static void requireExactIntent(PublishIntent expected, PublishIntent actual) {
    if (!expected.equals(actual)) {
      throw new DraftCommitIdentityConflictException(
          "Publish request identity was reused with changed notes, target, epoch, or commit selection");
    }
  }

  private void requireWritableReadCommittedTransaction() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(isolation)) {
      throw new IllegalStateException(
          "Authored Draft selection mutations require a caller-owned writable READ_COMMITTED transaction");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  private static boolean isUniqueConstraintViolation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException
          && "23505".equals(sqlException.getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  public record SelectionSnapshot(
      AuthoredDraftPublishSelection selection, OffsetDateTime createdAt) {
    public SelectionSnapshot {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }
}
