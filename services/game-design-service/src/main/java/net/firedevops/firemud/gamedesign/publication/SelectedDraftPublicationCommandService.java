package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered composition for authenticated selected-Draft admission and full-version publish.
 *
 * <p>Existing local operation bytes identify an already admitted attempt for exact reconciliation;
 * they are not current Account authorization. A new operation always enters the authenticated
 * Account and World admission path before the existing-version finalizer is called.
 */
public final class SelectedDraftPublicationCommandService {
  private final SelectedDraftPublicationAdmissionService admission;
  private final DurableStateReader durableStateReader;
  private final VersionPublishCommandServiceImpl finalizer;

  public SelectedDraftPublicationCommandService(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      AccountPublicationAuthorizationReadClient accountClient,
      WorldPublishedStartLocationClient worldClient,
      String workloadNamespace,
      VersionPublishCommandServiceImpl finalizer) {
    this(
        new SelectedDraftPublicationAdmissionService(
            dsl, transactionManager, accountClient, worldClient, workloadNamespace),
        new DatabaseDurableStateReader(dsl),
        finalizer);
  }

  SelectedDraftPublicationCommandService(
      SelectedDraftPublicationAdmissionService admission,
      DurableStateReader durableStateReader,
      VersionPublishCommandServiceImpl finalizer) {
    this.admission = Objects.requireNonNull(admission, "admission");
    this.durableStateReader = Objects.requireNonNull(durableStateReader, "durableStateReader");
    this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
  }

  /**
   * Publishes the exact selected existing Draft. The World request is an identity supplied for
   * first admission and must match the embedded request on exact retries.
   */
  public VersionDto publishSelectedDraftFullVersion(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(accountBinding, "accountBinding");
    Objects.requireNonNull(worldRequest, "worldRequest");
    requireIntentMatchesAccount(intent, accountBinding);

    DurableState state = durableStateReader.read(intent, accountBinding);
    boolean selectionExists = state.selection().isPresent();
    boolean operationExists = state.operation().isPresent();
    if (!selectionExists) {
      if (operationExists) {
        throw new IllegalStateException(
            "SELECTED_PUBLICATION_LOCAL_IDENTITY_INCOMPLETE: operation exists without its durable selection");
      }
      throw new IllegalStateException(
          "SELECTED_PUBLICATION_SELECTION_UNAVAILABLE: exact immutable selection must precede Account authorization");
    }

    AuthoredDraftPublishSelection selection = state.selection().orElseThrow();
    if (!operationExists) {
      // The immutable selection precedes Account authorization on the first publication entry.
      requireExactSelection(intent, accountBinding, worldRequest, selection);
      SelectedDraftPublicationOwner.Reservation reservation =
          admission.admitAndReserve(intent, accountBinding, worldRequest);
      Objects.requireNonNull(reservation, "selected Draft admission returned no reservation");
      if (!Arrays.equals(selection.canonicalBytes(), reservation.selection().canonicalBytes())) {
        throw new IllegalStateException(
            "SELECTED_PUBLICATION_RESERVATION_SELECTION_CONFLICT: admitted selection differs from the immutable preselection");
      }
      requireExactAdmission(intent, accountBinding, worldRequest, reservation);
      return finalizeSelectedDraft(intent, accountBinding, worldRequest, selection);
    }
    GameDesignPublicationOperationRepository.Readback operationReadback =
        state.operation().orElseThrow();
    requireExactRetry(intent, accountBinding, worldRequest, selection, operationReadback);
    return finalizeSelectedDraft(intent, accountBinding, worldRequest, selection);
  }

  private VersionDto finalizeSelectedDraft(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest,
      AuthoredDraftPublishSelection selection) {
    var target = selection.target();
    String workflowId = workflowId(accountBinding);
    if (!workflowId.equals(worldRequest.publishWorkflowId())) {
      throw new IllegalArgumentException(
          "Selected Draft publication workflow differs from the original World request");
    }
    return finalizer.publishSelectedDraftFullVersion(
        target.gameDesignVersionTenantKey(),
        target.gameDesignVersionRowId(),
        intent.notes(),
        intent.publishRequestId(),
        workflowId);
  }

  private static void requireExactRetry(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest,
      AuthoredDraftPublishSelection selection,
      GameDesignPublicationOperationRepository.Readback operationReadback) {
    requireExactSelection(intent, accountBinding, worldRequest, selection);
    GameDesignPublicationOperation operation = operationReadback.operation();
    if (!Arrays.equals(accountBinding.canonicalBytes(), operation.account().canonicalBytes())
        || !worldRequest.equals(operation.world().request())
        || !operation.workflowId().equals(workflowId(accountBinding))
        || !operation.tenantKey().equals(selection.target().gameDesignVersionTenantKey())
        || operation.versionId() != selection.target().gameDesignVersionRowId()
        || !operation.selectionDigest().equals(selection.digest())) {
      throw new IllegalStateException(
          "SELECTED_PUBLICATION_LOCAL_IDENTITY_CONFLICT: retry differs from the original intent, "
              + "Account binding, World request, selection, or release identity");
    }
    if (!"PENDING".equals(operationReadback.outcome())
        && !"PUBLISHED".equals(operationReadback.outcome())
        && !"NO_PUBLICATION".equals(operationReadback.outcome())) {
      throw new IllegalStateException(
          "SELECTED_PUBLICATION_LOCAL_OUTCOME_UNAVAILABLE: original publication outcome is "
              + "not reconcilable");
    }
  }

  private static void requireExactAdmission(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest,
      SelectedDraftPublicationOwner.Reservation reservation) {
    GameDesignPublicationOperation operation = reservation.operation();
    requireExactSelection(intent, accountBinding, worldRequest, reservation.selection());
    if (!Arrays.equals(accountBinding.canonicalBytes(), operation.account().canonicalBytes())
        || !worldRequest.equals(operation.world().request())
        || !operation.workflowId().equals(workflowId(accountBinding))
        || !operation.selectionDigest().equals(reservation.selection().digest())
        || operation.versionId() != reservation.selection().target().gameDesignVersionRowId()
        || !operation
            .tenantKey()
            .equals(reservation.selection().target().gameDesignVersionTenantKey())) {
      throw new IllegalStateException(
          "SELECTED_PUBLICATION_ADMISSION_READBACK_CONFLICT: authenticated admission differs from its exact inputs or selected release identity");
    }
  }

  private static void requireExactSelection(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest,
      AuthoredDraftPublishSelection selection) {
    var accountSelection = accountBinding.input().selection();
    if (!intent.equals(selection.intent())
        || !Arrays.equals(selection.canonicalBytes(), accountSelection.canonicalBytes())
        || !selection.digest().equals(accountSelection.digest())
        || !selection.target().canonicalTenantId().equals(worldRequest.canonicalTenantId())
        || !selection.target().canonicalVersionId().equals(worldRequest.canonicalVersionId())
        || !intent.publishRequestId().equals(worldRequest.publicationRequestId())
        || !intent
            .expectedVersionStateEpoch()
            .equals(Long.toString(worldRequest.versionStateEpoch()))
        || !selection.digest().equals("sha256:" + worldRequest.requestDigest())
        || !intent.selectedCommitId().toString().equals(worldRequest.appliedCommitId())
        || !workflowId(accountBinding).equals(worldRequest.publishWorkflowId())) {
      throw new IllegalStateException(
          "SELECTED_PUBLICATION_SELECTION_IDENTITY_CONFLICT: selection differs from the "
              + "complete original intent or owner requests");
    }
  }

  private static void requireIntentMatchesAccount(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding) {
    var accountIntent = accountBinding.input().selection().intent();
    var suppliedIntent =
        new AuthoredDraftPublishSelection.PublishIntent(
            accountIntent.canonicalTenantId(),
            accountIntent.canonicalVersionId(),
            accountIntent.publishRequestId(),
            accountIntent.expectedVersionStateEpoch(),
            accountIntent.notes(),
            accountIntent.selectedCommitRequestId(),
            accountIntent.selectedCommitId(),
            accountIntent.selectedCommitDigest());
    if (!intent.equals(suppliedIntent)) {
      throw new IllegalArgumentException(
          "Account binding must retain the complete supplied original selected Draft intent");
    }
  }

  private static String workflowId(AccountPublicationAuthorizationBinding accountBinding) {
    var selection = accountBinding.input().selection();
    return PublicationDigestRequestBinding.full(
            selection.intent().canonicalTenantId().toString(),
            Long.toString(selection.target().gameDesignVersionRowId()),
            selection.intent().publishRequestId())
        .derivedWorkflowIdentity();
  }

  private static String workflowId(AuthoredDraftPublishSelection selection) {
    return PublicationDigestRequestBinding.full(
            selection.target().canonicalTenantId().toString(),
            Long.toString(selection.target().gameDesignVersionRowId()),
            selection.intent().publishRequestId())
        .derivedWorkflowIdentity();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Selected Draft publication composition requires no ambient SQL transaction");
    }
  }

  @FunctionalInterface
  interface DurableStateReader {
    DurableState read(
        AuthoredDraftPublishSelection.PublishIntent intent,
        AccountPublicationAuthorizationBinding accountBinding);
  }

  record DurableState(
      Optional<AuthoredDraftPublishSelection> selection,
      Optional<GameDesignPublicationOperationRepository.Readback> operation) {
    DurableState {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(operation, "operation");
    }

    static DurableState absent() {
      return new DurableState(Optional.empty(), Optional.empty());
    }
  }

  private static final class DatabaseDurableStateReader implements DurableStateReader {
    private final AuthoredDraftPublishSelectionRepository selections;
    private final GameDesignPublicationOperationRepository operations;

    private DatabaseDurableStateReader(DSLContext dsl) {
      Objects.requireNonNull(dsl, "dsl");
      selections =
          new AuthoredDraftPublishSelectionRepository(
              dsl, new DraftCommitCoordinatorRepository(dsl));
      operations = new GameDesignPublicationOperationRepository(dsl);
    }

    @Override
    public DurableState read(
        AuthoredDraftPublishSelection.PublishIntent intent,
        AccountPublicationAuthorizationBinding accountBinding) {
      Optional<AuthoredDraftPublishSelection> selection =
          selections
              .readByPublishRequest(intent.canonicalTenantId(), intent.publishRequestId())
              .map(AuthoredDraftPublishSelectionRepository.SelectionSnapshot::selection);
      String retainedWorkflow =
          selection
              .map(SelectedDraftPublicationCommandService::workflowId)
              .orElseGet(() -> workflowId(accountBinding));
      return new DurableState(selection, operations.read(retainedWorkflow));
    }
  }
}
