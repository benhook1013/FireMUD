package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeClient;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Game Design admission boundary for one exact selected-Draft publication. Account
 * authorization, World freeze and selector capture finish before the local reservation transaction.
 */
public final class SelectedDraftPublicationAdmissionService {
  private final AccountPublicationAuthorizationReadClient accountClient;
  private final WorldPublishedStartLocationClient worldClient;
  private final WorldSelectedDraftPublicationFreezeClient freezeClient;
  private final WorldSelectedPublicationArtifactInventoryClient inventoryClient;
  private final SelectionReader selectionReader;
  private final SelectedDraftPublicationOwner owner;
  private final WorldAuthoredSourceIntakeClient worldSourceIntakeClient;
  private final GameAuthoredWorldSourceRepository worldSourceRepository;
  private final GameAuthoredWorldSourceDeliveryRepository worldSourceDeliveryRepository;
  private final TransactionTemplate reservationTransaction;
  private final String workloadNamespace;

  public SelectedDraftPublicationAdmissionService(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      AccountPublicationAuthorizationReadClient accountClient,
      WorldSelectedDraftPublicationFreezeClient freezeClient,
      WorldSelectedPublicationArtifactInventoryClient inventoryClient,
      WorldPublishedStartLocationClient worldClient,
      WorldAuthoredSourceIntakeClient worldSourceIntakeClient,
      String workloadNamespace) {
    this(
        transactionManager,
        accountClient,
        freezeClient,
        inventoryClient,
        worldClient,
        worldSourceIntakeClient,
        new GameAuthoredWorldSourceRepository(Objects.requireNonNull(dsl, "dsl")),
        new GameAuthoredWorldSourceDeliveryRepository(dsl),
        new SelectedDraftPublicationOwner(Objects.requireNonNull(dsl, "dsl")),
        intent ->
            new AuthoredDraftPublishSelectionRepository(
                    dsl, new DraftCommitCoordinatorRepository(dsl))
                .read(
                    intent.canonicalTenantId(),
                    intent.canonicalVersionId(),
                    intent.publishRequestId())
                .map(AuthoredDraftPublishSelectionRepository.SelectionSnapshot::selection),
        workloadNamespace);
  }

  SelectedDraftPublicationAdmissionService(
      PlatformTransactionManager transactionManager,
      AccountPublicationAuthorizationReadClient accountClient,
      WorldSelectedDraftPublicationFreezeClient freezeClient,
      WorldSelectedPublicationArtifactInventoryClient inventoryClient,
      WorldPublishedStartLocationClient worldClient,
      WorldAuthoredSourceIntakeClient worldSourceIntakeClient,
      GameAuthoredWorldSourceRepository worldSourceRepository,
      GameAuthoredWorldSourceDeliveryRepository worldSourceDeliveryRepository,
      SelectedDraftPublicationOwner owner,
      SelectionReader selectionReader,
      String workloadNamespace) {
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient");
    this.worldClient = Objects.requireNonNull(worldClient, "worldClient");
    this.freezeClient = Objects.requireNonNull(freezeClient, "freezeClient");
    this.inventoryClient = Objects.requireNonNull(inventoryClient, "inventoryClient");
    this.worldSourceIntakeClient =
        Objects.requireNonNull(worldSourceIntakeClient, "worldSourceIntakeClient");
    this.worldSourceRepository =
        Objects.requireNonNull(worldSourceRepository, "worldSourceRepository");
    this.worldSourceDeliveryRepository =
        Objects.requireNonNull(worldSourceDeliveryRepository, "worldSourceDeliveryRepository");
    this.selectionReader = Objects.requireNonNull(selectionReader, "selectionReader");
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
   * Verifies the retained selection, authenticates Account HELD, freezes World, and reads the exact
   * selector and complete source receipt before reserving locally. All owner reads complete outside
   * SQL.
   */
  public SelectedDraftPublicationOwner.Reservation admitAndReserve(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(accountBinding, "accountBinding");
    AuthoredDraftPublishSelection selection =
        selectionReader
            .read(intent)
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_SELECTION_UNAVAILABLE"));
    requireRequestIdentity(intent, accountBinding, selection);

    AccountPublicationAuthorizationReadEvidence.Request accountReadRequest =
        AccountPublicationAuthorizationReadEvidence.Request.create(
            workloadNamespace, accountBinding);
    AccountPublicationAuthorizationReadEvidence accountEvidence =
        accountClient.read(accountReadRequest);
    requireExactAccountReadback(accountReadRequest, accountBinding, accountEvidence);

    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            workloadNamespace,
            selection.target().canonicalTenantId(),
            selection.target().canonicalVersionId(),
            intent.publishRequestId(),
            Long.parseLong(intent.expectedVersionStateEpoch()),
            selection.digest().substring("sha256:".length()),
            accountEvidence.request().binding());
    var freeze = freezeClient.begin(freezeRequest);
    if (freeze == null
        || !freezeRequest.equals(freeze.request())
        || !freezeRequest.equals(freeze.acknowledgement().request())) {
      throw new IllegalStateException(
          "World publication freeze readback differs from the exact request");
    }
    var acknowledgement = freeze.acknowledgement();
    var inventory = inventoryClient.read(freeze);
    if (inventory == null
        || !freeze.request().equals(inventory.freezeEvidence().request())
        || !freeze.acknowledgement().equals(inventory.freezeEvidence().acknowledgement())) {
      throw new IllegalStateException(
          "World inventory readback differs from exact acknowledged freeze");
    }
    var worldRequest =
        new WorldPublishedStartLocationEvidence.Request(
            workloadNamespace,
            selection.target().canonicalTenantId(),
            selection.target().canonicalVersionId(),
            acknowledgement.intakeRequestId(),
            acknowledgement.publicationFence(),
            intent.publishRequestId(),
            freezeRequest.requestDigest(),
            acknowledgement.versionStateEpoch(),
            PublicationDigestRequestBinding.full(
                    intent.canonicalTenantId().toString(),
                    Long.toString(selection.target().gameDesignVersionRowId()),
                    intent.publishRequestId())
                .derivedWorkflowIdentity(),
            acknowledgement.appliedCommitId(),
            acknowledgement.contentDigest(),
            acknowledgement.digestSchemaVersion(),
            selection
                .selectedCommit()
                .affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT)
                .stream()
                .map(
                    unit ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            unit.owner().name(),
                            unit.aggregateType(),
                            unit.aggregateId(),
                            unit.scopeType(),
                            unit.scopeId(),
                            unit.expectedEpoch()))
                .toList());
    WorldPublishedStartLocationEvidence worldEvidence = worldClient.read(worldRequest);
    requireExactWorldReadback(worldRequest, worldEvidence);
    new GameDesignPublicationOperationBinding(
        accountEvidence.request().binding(), worldEvidence, inventory);
    SelectedDraftTemplateWorldSourceAssociation.SourceRead worldSourceRead =
        readAndVerifyWorldSource(worldEvidence, selection.target());

    return Objects.requireNonNull(
        reservationTransaction.execute(
            status ->
                owner.reserve(
                    intent,
                    accountEvidence.request().binding(),
                    worldEvidence,
                    inventory,
                    worldSourceRead)),
        "Selected Draft publication reservation returned no result");
  }

  private SelectedDraftTemplateWorldSourceAssociation.SourceRead readAndVerifyWorldSource(
      WorldPublishedStartLocationEvidence worldEvidence,
      DraftCommitBinding.TargetProof selectedTarget) {
    var selected = worldEvidence.request();
    java.util.UUID readRequestId = java.util.UUID.randomUUID();
    while (readRequestId.equals(selected.intakeRequestId())) {
      readRequestId = java.util.UUID.randomUUID();
    }
    var request =
        new ByIdReadRequest(
            1,
            selected.targetNamespace(),
            readRequestId,
            selected.intakeRequestId(),
            selected.canonicalTenantId());
    var receipt = worldSourceIntakeClient.readById(request);
    var worldRead = new SelectedDraftTemplateWorldSourceAssociation.SourceRead(request, receipt);
    var source =
        worldSourceRepository
            .read(
                receipt.sourceOperationId(),
                receipt.canonicalTenantId(),
                receipt.worldSlug(),
                selected.targetNamespace())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_WORLD_SOURCE_GD_RECORD_UNAVAILABLE"));
    var delivery =
        worldSourceDeliveryRepository
            .read(receipt.sourceOperationId())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_WORLD_SOURCE_DELIVERY_UNAVAILABLE"));
    var expectedReceipt =
        new CommittedReceipt(
            receipt.schemaVersion(),
            receipt.targetNamespace(),
            receipt.intakeRequestId(),
            receipt.operationId(),
            receipt.canonicalTenantId(),
            receipt.worldSlug(),
            receipt.sourceOperationId(),
            receipt.sourceEvidenceDigest(),
            receipt.requestDigest(),
            receipt.receiptDigest());
    if (!source.equals(receipt.source())
        || !delivery.source().equals(source)
        || !delivery.request().targetNamespace().equals(selected.targetNamespace())
        || !delivery.request().intakeRequestId().equals(selected.intakeRequestId())
        || !delivery.request().canonicalTenantId().equals(selected.canonicalTenantId())
        || !delivery.request().worldSlug().equals(receipt.worldSlug())
        || !delivery.request().sourceOperationId().equals(receipt.sourceOperationId())
        || !delivery.request().expectedSourceEvidenceDigest().equals(receipt.sourceEvidenceDigest())
        || source.sourceGameRowId() != selectedTarget.sourceGameRowId()
        || !source.sourceGameTenantKey().equals(selectedTarget.sourceGameTenantKey())
        || !source.provenanceKind().equals(selectedTarget.sourceProvenanceKind())
        || !WorldAuthoredSourceIntakeGrpcCodec.requestDigest(delivery.request())
            .equals(receipt.requestDigest())
        || delivery.acknowledgedReceipt().filter(ack -> !ack.equals(expectedReceipt)).isPresent()) {
      throw new IllegalStateException(
          "SELECTED_WORLD_SOURCE_OWNER_READBACK_CONFLICT: World, retained source, and original delivery differ");
    }
    return worldRead;
  }

  private void requireRequestIdentity(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AccountPublicationAuthorizationBinding accountBinding,
      AuthoredDraftPublishSelection selection) {
    var intentFromBinding = accountBinding.input().selection().intent();
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
    if (!expectedIntent.equals(intentFromBinding)
        || !intent.equals(selection.intent())
        || !Arrays.equals(
            selection.canonicalBytes(), accountBinding.input().selection().canonicalBytes())) {
      throw new IllegalArgumentException(
          "Account publication binding and retained selection must match the exact selected Draft intent");
    }
  }

  @FunctionalInterface
  interface SelectionReader {
    Optional<AuthoredDraftPublishSelection> read(
        AuthoredDraftPublishSelection.PublishIntent intent);
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
