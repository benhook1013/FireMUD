package net.firedevops.firemud.gamedesign.publication;

import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Game Design admission boundary for one exact selected-Draft publication. Both owner
 * reads complete and are correlated before the local reservation transaction begins.
 */
public final class SelectedDraftPublicationAdmissionService {
  private final AccountPublicationAuthorizationReadClient accountClient;
  private final WorldPublishedStartLocationClient worldClient;
  private final SelectedDraftPublicationOwner owner;
  private final TransactionTemplate reservationTransaction;
  private final String workloadNamespace;

  public SelectedDraftPublicationAdmissionService(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      AccountPublicationAuthorizationReadClient accountClient,
      WorldPublishedStartLocationClient worldClient,
      String workloadNamespace) {
    this(
        transactionManager,
        accountClient,
        worldClient,
        new SelectedDraftPublicationOwner(Objects.requireNonNull(dsl, "dsl")),
        workloadNamespace);
  }

  SelectedDraftPublicationAdmissionService(
      PlatformTransactionManager transactionManager,
      AccountPublicationAuthorizationReadClient accountClient,
      WorldPublishedStartLocationClient worldClient,
      SelectedDraftPublicationOwner owner,
      String workloadNamespace) {
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient");
    this.worldClient = Objects.requireNonNull(worldClient, "worldClient");
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.reservationTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.reservationTransaction.setName("selected-draft-publication-reservation");
    this.reservationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.reservationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.reservationTransaction.setReadOnly(false);
  }

  /**
   * Authenticates exact HELD Account and World readbacks before reserving their unchanged inputs.
   * The caller-supplied binding and World request are lookup identities only, never authority.
   */
  public SelectedDraftPublicationOwner.Reservation admitAndReserve(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(accountBinding, "accountBinding");
    Objects.requireNonNull(worldRequest, "worldRequest");
    requireRequestIdentity(intent, accountBinding, worldRequest);

    AccountPublicationAuthorizationReadEvidence.Request accountReadRequest =
        AccountPublicationAuthorizationReadEvidence.Request.create(
            workloadNamespace, accountBinding);
    AccountPublicationAuthorizationReadEvidence accountEvidence =
        accountClient.read(accountReadRequest);
    requireExactAccountReadback(accountReadRequest, accountBinding, accountEvidence);

    WorldPublishedStartLocationEvidence worldEvidence = worldClient.read(worldRequest);
    requireExactWorldReadback(worldRequest, worldEvidence);
    new GameDesignPublicationOperationBinding(accountEvidence.request().binding(), worldEvidence);

    return Objects.requireNonNull(
        reservationTransaction.execute(
            status -> owner.reserve(intent, accountEvidence.request().binding(), worldEvidence)),
        "Selected Draft publication reservation returned no result");
  }

  private void requireRequestIdentity(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldPublishedStartLocationEvidence.Request worldRequest) {
    var selection = accountBinding.input().selection();
    var intentFromBinding = selection.intent();
    var expectedIntent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            intent.canonicalTenantId(),
            intent.canonicalVersionId(),
            intent.publishRequestId(),
            intent.expectedVersionStateEpoch(),
            intent.notes(),
            intent.selectedCommitRequestId(),
            intent.selectedCommitId(),
            intent.selectedCommitDigest());
    String expectedWorkflowId =
        PublicationDigestRequestBinding.full(
                intent.canonicalTenantId().toString(),
                Long.toString(selection.target().gameDesignVersionRowId()),
                intent.publishRequestId())
            .derivedWorkflowIdentity();
    String expectedRequestDigest = selection.digest().substring("sha256:".length());

    if (!expectedIntent.equals(intentFromBinding)
        || !workloadNamespace.equals(worldRequest.targetNamespace())
        || !intent.canonicalTenantId().equals(worldRequest.canonicalTenantId())
        || !intent.canonicalVersionId().equals(worldRequest.canonicalVersionId())
        || !intent.publishRequestId().equals(worldRequest.publicationRequestId())
        || !Long.toString(worldRequest.versionStateEpoch())
            .equals(intent.expectedVersionStateEpoch())
        || !expectedWorkflowId.equals(worldRequest.publishWorkflowId())
        || !intent.selectedCommitId().toString().equals(worldRequest.appliedCommitId())
        || !expectedRequestDigest.equals(worldRequest.requestDigest())) {
      throw new IllegalArgumentException(
          "Account publication binding and World selector request must match the exact selected Draft intent");
    }
  }

  private static void requireExactAccountReadback(
      AccountPublicationAuthorizationReadEvidence.Request expectedRequest,
      AccountPublicationAuthorizationBinding expectedBinding,
      AccountPublicationAuthorizationReadEvidence evidence) {
    if (evidence == null
        || !expectedRequest.equals(evidence.request())
        || !java.util.Arrays.equals(
            expectedBinding.canonicalBytes(), evidence.request().binding().canonicalBytes())) {
      throw new IllegalStateException(
          "Account publication authorization readback differs from the exact request");
    }
  }

  private static void requireExactWorldReadback(
      WorldPublishedStartLocationEvidence.Request expectedRequest,
      WorldPublishedStartLocationEvidence evidence) {
    if (evidence == null || !expectedRequest.equals(evidence.request())) {
      throw new IllegalStateException(
          "World publication selector readback differs from the exact request");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Selected Draft publication owner reads require no ambient transaction");
    }
  }
}
