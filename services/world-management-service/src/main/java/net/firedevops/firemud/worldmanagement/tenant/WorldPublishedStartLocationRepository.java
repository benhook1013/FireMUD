package net.firedevops.firemud.worldmanagement.tenant;

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
    var selected = frozen.readCommitted(Objects.requireNonNull(request, "request"));
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
    var applied =
        applications
            .readCommitted(request.targetNamespace(), account.canonicalBytes())
            .orElseThrow(
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
}
