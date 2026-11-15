package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicitly unwired immutable frozen-selection to original APPLIED selector join. */
public final class WorldPublishedStartLocationRepository {
  private final DSLContext dsl;
  private final WorldCanonicalFrozenTopologyRepository frozen;
  private final WorldDraftGraphApplicationRepository applications;

  public WorldPublishedStartLocationRepository(
      DSLContext dsl,
      WorldCanonicalFrozenTopologyRepository frozen,
      WorldDraftGraphApplicationRepository applications) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.frozen = Objects.requireNonNull(frozen, "frozen");
    this.applications = Objects.requireNonNull(applications, "applications");
  }

  /** Immutable journals need no distributed transaction; absence never authorizes a fallback. */
  public Optional<WorldPublishedStartLocationSource> readCommitted(CaptureRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("World published selector read requires no caller transaction");
    }
    return readSource(Objects.requireNonNull(request, "request"), false);
  }

  /** Joins both immutable journals within one caller-owned read-only RR snapshot. */
  Optional<WorldPublishedStartLocationSource> readInOwnedSnapshot(CaptureRequest request) {
    requireOwnedReadOnlyRepeatableReadSnapshot();
    return readSource(Objects.requireNonNull(request, "request"), true);
  }

  private Optional<WorldPublishedStartLocationSource> readSource(
      CaptureRequest request, boolean ownedSnapshot) {
    var selected =
        ownedSnapshot ? frozen.readInOwnedSnapshot(request) : frozen.readCommitted(request);
    if (selected.isEmpty()) return Optional.empty();
    var capture = selected.orElseThrow();
    var plan = capture.request().plan();
    if (!request.equals(capture.request().freeze())) {
      throw new ConflictException("World published selector differs from exact frozen selection");
    }
    var rows =
        dsl.fetch(
            "SELECT account_binding_bytes FROM world_draft_graph_application WHERE request_id=? OR commit_id=?",
            plan.binding().requestId(),
            plan.binding().commitId());
    if (rows.isEmpty()) {
      throw new ConflictException("World published selector lacks original APPLIED application");
    }
    if (rows.size() != 1) {
      throw new ConflictException("World published selector selects conflicting applications");
    }
    var account =
        DraftAuthorizationFenceBinding.fromStored(
            rows.getFirst().get("account_binding_bytes", byte[].class));
    var appliedRead =
        ownedSnapshot
            ? applications.readInOwnedSnapshot(request.targetNamespace(), account.canonicalBytes())
            : applications.readCommitted(request.targetNamespace(), account.canonicalBytes());
    var applied =
        appliedRead.orElseThrow(
            () -> new ConflictException("World published selector lacks committed APPLIED"));
    var application = applied.application();
    var receipt =
        applied
            .startLocationReceipt()
            .orElseThrow(
                () ->
                    new ConflictException(
                        "World published selector lacks original selector receipt"));
    if (!plan.binding().equals(application.plan().binding())
        || !plan.ownerBinding().equals(application.plan().ownerBinding())
        || !Arrays.equals(account.canonicalBytes(), application.operation().accountBindingBytes())
        || !request.appliedCommitId().equals(application.operation().commitId().toString())
        || !Arrays.equals(capture.graphBytes(), applied.graphBytes())
        || !receipt.graphDigest().equals(WorldDraftGraphAppliedResult.digest(capture.graphBytes()))
        || !receipt.equals(
            WorldDraftStartLocationReceipt.create(application, capture.graphBytes()))) {
      throw new ConflictException(
          "World published selector differs from original frozen application");
    }
    return Optional.of(new WorldPublishedStartLocationSource(capture, applied, receipt));
  }

  private void requireOwnedReadOnlyRepeatableReadSnapshot() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new ConflictException(
          "World published selector snapshot read requires an active read-only REPEATABLE READ owner transaction");
    }
    Integer jdbcIsolation = dsl.connectionResult(Connection::getTransactionIsolation);
    if (!Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(jdbcIsolation)) {
      throw new ConflictException(
          "World published selector snapshot read requires actual JDBC REPEATABLE READ isolation");
    }
  }
}
