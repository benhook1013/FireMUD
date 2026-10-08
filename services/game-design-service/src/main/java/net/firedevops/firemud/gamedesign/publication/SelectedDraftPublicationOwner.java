package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflow;
import org.jooq.DSLContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Game Design owner entry for reserving publication of one existing synchronized
 * Draft. It persists exact local selection/attempt/source state; supplied Account and World binding
 * objects are structured evidence, not proof that either remote owner authenticated its producer. A
 * production caller must obtain them from the authenticated Account publication producer and the
 * protected World selector read. Isolated fixtures may supply synthetic values only to test this
 * owner-local boundary.
 */
public final class SelectedDraftPublicationOwner {
  private final GameRepository games;
  private final VersionRepository versions;
  private final AuthoredDraftPublishSelectionRepository selections;
  private final PublishAttemptRepository attempts;
  private final GameDesignPublicationOperationRepository operations;

  public SelectedDraftPublicationOwner(DSLContext dsl) {
    Objects.requireNonNull(dsl, "dsl");
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    games = new GameRepository(dsl);
    versions = new VersionRepository(dsl);
    selections = new AuthoredDraftPublishSelectionRepository(dsl, coordinator);
    attempts = new PublishAttemptRepository(dsl);
    operations = new GameDesignPublicationOperationRepository(dsl);
  }

  /**
   * Retains a first or exact-replay selection and the original external publication inputs in one
   * caller-owned writable READ_COMMITTED transaction. The immutable selection determines attempt
   * identity; an old full-publish digest is never rewritten or upgraded here.
   */
  public Reservation reserve(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding account,
      WorldPublishedStartLocationEvidence world) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(world, "world");

    Version identityCandidate =
        versions
            .findByCanonicalTenantIdAndCanonicalVersionId(
                intent.canonicalTenantId(), intent.canonicalVersionId())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_VERSION_UNAVAILABLE"));
    String tenantKey = identityCandidate.getTenantId();
    var game = games.findByTenantIdForUpdate(tenantKey);
    if (game == null
        || !intent.canonicalTenantId().equals(game.getCanonicalTenantId())
        || !intent.canonicalTenantId().equals(identityCandidate.getCanonicalTenantId())) {
      throw new IllegalStateException("SELECTED_PUBLICATION_GAME_IDENTITY_CHANGED");
    }

    var selectionSnapshot = selections.reserve(intent);
    var selection = selectionSnapshot.selection();
    var selectedBinding = account.input().selection();
    if (!Arrays.equals(selection.canonicalBytes(), selectedBinding.canonicalBytes())
        || !selection.digest().equals(selectedBinding.digest())) {
      throw new IllegalStateException("SELECTED_PUBLICATION_SELECTION_CHANGED");
    }

    var operation = new GameDesignPublicationOperation(account, world);
    String workflowId =
        FiremudWorkflowIds.workflowId(
            TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
            selection.target().gameDesignVersionTenantKey(),
            "publish-request",
            intent.publishRequestId());
    if (!workflowId.equals(operation.workflowId())
        || !selection.target().canonicalTenantId().equals(world.request().canonicalTenantId())
        || !selection.target().canonicalVersionId().equals(world.request().canonicalVersionId())
        || !intent.publishRequestId().equals(world.request().publicationRequestId())
        || !intent
            .expectedVersionStateEpoch()
            .equals(Long.toString(world.request().versionStateEpoch()))
        || !selection
            .selectedCommit()
            .commitId()
            .toString()
            .equals(world.request().appliedCommitId())
        || !selection.digest().equals("sha256:" + world.request().requestDigest())) {
      throw new IllegalStateException("SELECTED_PUBLICATION_OPERATION_BINDING_CHANGED");
    }

    if (!tenantKey.equals(selection.target().gameDesignVersionTenantKey())) {
      throw new IllegalStateException("SELECTED_PUBLICATION_TENANT_KEY_CHANGED");
    }
    Version version =
        versions
            .findByTenantIdAndIdForUpdate(tenantKey, selection.target().gameDesignVersionRowId())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_VERSION_UNAVAILABLE"));
    requireExactSelectedVersion(selection, version);

    PublishAttempt attempt =
        attempts.findByPublishWorkflowIdForUpdate(operation.workflowId()).orElse(null);
    if (attempt == null) {
      attempt = new PublishAttempt();
      attempt.setTenantId(tenantKey);
      attempt.setPublishWorkflowId(operation.workflowId());
      attempt.setPublishType(PublishType.FULL_VERSION);
      attempt.setVersionId(version.getId());
      attempt.setVersionNumber(version.getVersionNumber());
      attempt.setRequestDigest(selection.digest());
      attempt = attempts.save(attempt);
    } else {
      requireExactSelectedAttempt(attempt, operation, version, selection.digest());
      if (attempt.getStatus() != PublishAttemptStatus.PENDING) {
        throw new IllegalStateException("SELECTED_PUBLICATION_ATTEMPT_TERMINAL");
      }
    }

    var capture = operations.reserveSourceBacked(operation);
    var retained =
        operations
            .read(operation.workflowId())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_READBACK_UNAVAILABLE"));
    var retainedSelection =
        selections
            .read(
                intent.canonicalTenantId(), intent.canonicalVersionId(), intent.publishRequestId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "SELECTED_PUBLICATION_SELECTION_READBACK_UNAVAILABLE"))
            .selection();
    if (!Arrays.equals(operation.canonicalBytes(), retained.operation().canonicalBytes())
        || !"PENDING".equals(retained.outcome())
        || !Arrays.equals(selection.canonicalBytes(), retainedSelection.canonicalBytes())
        || !capture.command().operation().equals(operation)
        || !capture.policy().operation().equals(operation)) {
      throw new IllegalStateException("SELECTED_PUBLICATION_RESERVATION_READBACK_CONFLICT");
    }
    long attemptId =
        Objects.requireNonNull(attempt.getId(), "reserved publication attempt must be persisted");
    return new Reservation(selection, operation, attemptId, capture);
  }

  private static void requireExactSelectedVersion(
      net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection selection,
      Version version) {
    var target = selection.target();
    var actualTarget =
        new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    if (!target.equals(actualTarget)
        || version.getVersionState() != VersionLifecycleState.DRAFT
        || version.isScriptOnly()
        || version.getBaseVersionId() != null
        || version.getScriptPatchVersion() != null
        || !Long.toString(version.getVersionStateEpoch())
            .equals(selection.intent().expectedVersionStateEpoch())) {
      throw new IllegalStateException("SELECTED_PUBLICATION_VERSION_CHANGED");
    }
  }

  private static void requireExactSelectedAttempt(
      PublishAttempt attempt,
      GameDesignPublicationOperation operation,
      Version version,
      String selectionDigest) {
    if (!Objects.equals(attempt.getTenantId(), operation.tenantKey())
        || !Objects.equals(attempt.getPublishWorkflowId(), operation.workflowId())
        || attempt.getPublishType() != PublishType.FULL_VERSION
        || !Objects.equals(attempt.getVersionId(), version.getId())
        || attempt.getVersionNumber() != version.getVersionNumber()
        || !Objects.equals(attempt.getRequestDigest(), selectionDigest)) {
      throw new IllegalStateException("SELECTED_PUBLICATION_ATTEMPT_IDENTITY_CONFLICT");
    }
  }

  private static void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Selected Draft publication reservation requires caller-owned writable READ_COMMITTED transaction");
    }
  }

  public record Reservation(
      net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection selection,
      GameDesignPublicationOperation operation,
      long attemptId,
      GameDesignSourceRepository.Capture sourceCapture) {
    public Reservation {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(operation);
      if (attemptId <= 0) {
        throw new IllegalArgumentException("reserved publication attempt ID must be positive");
      }
      Objects.requireNonNull(sourceCapture);
    }
  }
}
