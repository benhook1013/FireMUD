package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeClient;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit proof for the admission ordering and exact preflight checks; clients are transport doubles.
 */
class SelectedDraftPublicationAdmissionServiceTest {
  private static final String NAMESPACE = "test";

  @Test
  void retainedSelectionAccountFreezeAndDerivedSelectorPrecedeReservation() throws Exception {
    var fixture = fixture();
    var account = mock(AccountPublicationAuthorizationReadClient.class);
    var freeze = freezeClient(fixture);
    var inventory = inventoryClient(fixture);
    var world = mock(WorldPublishedStartLocationClient.class);
    var sourceIntake = mock(WorldAuthoredSourceIntakeClient.class);
    var sourceRepository = mock(GameAuthoredWorldSourceRepository.class);
    var deliveryRepository = mock(GameAuthoredWorldSourceDeliveryRepository.class);
    var sourceFixture = sourceFixture(fixture.worldRequest());
    when(sourceIntake.readById(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return sourceReceipt(invocation.getArgument(0), sourceFixture);
            });
    when(sourceRepository.read(
            sourceFixture.source().operationId(),
            sourceFixture.source().canonicalTenantId(),
            sourceFixture.source().worldSlug(),
            NAMESPACE))
        .thenReturn(Optional.of(sourceFixture.source()));
    when(deliveryRepository.read(sourceFixture.source().operationId()))
        .thenReturn(
            Optional.of(
                new GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim(
                    sourceFixture.source(), sourceFixture.intakeRequest(), Optional.empty())));
    var owner = mock(SelectedDraftPublicationOwner.class);
    var reader = mock(SelectedDraftPublicationAdmissionService.SelectionReader.class);
    var transactions = mock(PlatformTransactionManager.class);
    var retained =
        AuthoredDraftPublishSelection.fromStored(
            fixture.operation().account().input().selection().canonicalJson(),
            fixture.operation().account().input().selection().digest());
    when(reader.read(fixture.intent())).thenReturn(Optional.of(retained));
    when(account.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
              when(evidence.request()).thenReturn(invocation.getArgument(0));
              return evidence;
            });
    when(world.read(fixture.worldRequest()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return fixture.operation().world();
            });
    var reservation = mock(SelectedDraftPublicationOwner.Reservation.class);
    // Request.binding() reconstructs the exact Account bytes; its nested selection does not use
    // value equality. Match the complete canonical binding rather than decoded object identity.
    when(owner.reserve(
            eq(fixture.intent()),
            argThat(
                binding ->
                    Arrays.equals(
                        binding.canonicalBytes(), fixture.operation().account().canonicalBytes())),
            same(fixture.operation().world()),
            same(fixture.operation().inventory()),
            any()))
        .thenReturn(reservation);
    var service =
        new SelectedDraftPublicationAdmissionService(
            transactions,
            account,
            freeze,
            inventory,
            world,
            sourceIntake,
            sourceRepository,
            deliveryRepository,
            owner,
            reader,
            NAMESPACE);

    assertThat(service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isSameAs(reservation);
    var order =
        inOrder(
            reader,
            account,
            freeze,
            inventory,
            world,
            sourceIntake,
            sourceRepository,
            deliveryRepository,
            transactions,
            owner);
    order.verify(reader).read(fixture.intent());
    order.verify(account).read(any());
    order.verify(freeze).begin(any());
    order.verify(inventory).read(any());
    order.verify(world).read(fixture.worldRequest());
    order
        .verify(sourceIntake)
        .readById(
            argThat(
                request ->
                    request.schemaVersion() == 1
                        && request.targetNamespace().equals(NAMESPACE)
                        && request
                            .intakeRequestId()
                            .equals(fixture.worldRequest().intakeRequestId())
                        && request
                            .canonicalTenantId()
                            .equals(fixture.worldRequest().canonicalTenantId())
                        && !request.readRequestId().equals(request.intakeRequestId())));
    order
        .verify(sourceRepository)
        .read(
            sourceFixture.source().operationId(),
            sourceFixture.source().canonicalTenantId(),
            sourceFixture.source().worldSlug(),
            NAMESPACE);
    order.verify(deliveryRepository).read(sourceFixture.source().operationId());
    order.verify(transactions).getTransaction(any());
    order
        .verify(owner)
        .reserve(
            eq(fixture.intent()),
            argThat(
                binding ->
                    Arrays.equals(
                        binding.canonicalBytes(), fixture.operation().account().canonicalBytes())),
            same(fixture.operation().world()),
            same(fixture.operation().inventory()),
            any());
  }

  @Test
  void missingRetainedSelectionNeverReadsAccountFreezesWorldOrOpensSql() throws Exception {
    var fixture = fixture();
    var account = mock(AccountPublicationAuthorizationReadClient.class);
    var freeze = mock(WorldSelectedDraftPublicationFreezeClient.class);
    var inventory = mock(WorldSelectedPublicationArtifactInventoryClient.class);
    var world = mock(WorldPublishedStartLocationClient.class);
    var sourceIntake = mock(WorldAuthoredSourceIntakeClient.class);
    var sourceRepository = mock(GameAuthoredWorldSourceRepository.class);
    var deliveryRepository = mock(GameAuthoredWorldSourceDeliveryRepository.class);
    var owner = mock(SelectedDraftPublicationOwner.class);
    var transactions = mock(PlatformTransactionManager.class);
    var service =
        new SelectedDraftPublicationAdmissionService(
            transactions,
            account,
            freeze,
            inventory,
            world,
            sourceIntake,
            sourceRepository,
            deliveryRepository,
            owner,
            intent -> Optional.empty(),
            NAMESPACE);

    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTION_UNAVAILABLE");
    verifyNoInteractions(
        account,
        freeze,
        inventory,
        world,
        sourceIntake,
        sourceRepository,
        deliveryRepository,
        owner,
        transactions);
  }

  @Test
  void mismatchedRetainedDeliveryAcknowledgementDeniesBeforeReservation() throws Exception {
    var fixture = fixture();
    var account = mock(AccountPublicationAuthorizationReadClient.class);
    when(account.read(any()))
        .thenAnswer(
            invocation -> {
              var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
              when(evidence.request()).thenReturn(invocation.getArgument(0));
              return evidence;
            });
    var sourceFixture = sourceFixture(fixture.worldRequest());
    var sourceIntake = mock(WorldAuthoredSourceIntakeClient.class);
    when(sourceIntake.readById(any()))
        .thenAnswer(invocation -> sourceReceipt(invocation.getArgument(0), sourceFixture));
    var sourceRepository = mock(GameAuthoredWorldSourceRepository.class);
    when(sourceRepository.read(
            sourceFixture.source().operationId(),
            sourceFixture.source().canonicalTenantId(),
            sourceFixture.source().worldSlug(),
            NAMESPACE))
        .thenReturn(Optional.of(sourceFixture.source()));
    var receipt =
        sourceReceipt(
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                fixture.worldRequest().intakeRequestId(),
                fixture.worldRequest().canonicalTenantId()),
            sourceFixture);
    var wrongAcknowledgement =
        new WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt(
            receipt.schemaVersion(),
            receipt.targetNamespace(),
            receipt.intakeRequestId(),
            receipt.operationId(),
            receipt.canonicalTenantId(),
            receipt.worldSlug(),
            receipt.sourceOperationId(),
            receipt.sourceEvidenceDigest(),
            receipt.requestDigest(),
            "sha256:" + "b".repeat(64));
    var deliveryRepository = mock(GameAuthoredWorldSourceDeliveryRepository.class);
    when(deliveryRepository.read(sourceFixture.source().operationId()))
        .thenReturn(
            Optional.of(
                new GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim(
                    sourceFixture.source(),
                    sourceFixture.intakeRequest(),
                    Optional.of(wrongAcknowledgement))));
    var world = mock(WorldPublishedStartLocationClient.class);
    when(world.read(fixture.worldRequest())).thenReturn(fixture.operation().world());
    var owner = mock(SelectedDraftPublicationOwner.class);
    var transactions = mock(PlatformTransactionManager.class);
    var service =
        new SelectedDraftPublicationAdmissionService(
            transactions,
            account,
            freezeClient(fixture),
            inventoryClient(fixture),
            world,
            sourceIntake,
            sourceRepository,
            deliveryRepository,
            owner,
            intent ->
                Optional.of(
                    AuthoredDraftPublishSelection.fromStored(
                        fixture.operation().account().input().selection().canonicalJson(),
                        fixture.operation().account().input().selection().digest())),
            NAMESPACE);

    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OWNER_READBACK_CONFLICT");
    verifyNoInteractions(transactions, owner);
  }

  @Test
  void freezeFailureNeverReadsSelectorOrReserves() throws Exception {
    var fixture = fixture();
    var account = mock(AccountPublicationAuthorizationReadClient.class);
    var freeze = mock(WorldSelectedDraftPublicationFreezeClient.class);
    var inventory = mock(WorldSelectedPublicationArtifactInventoryClient.class);
    var world = mock(WorldPublishedStartLocationClient.class);
    var sourceIntake = mock(WorldAuthoredSourceIntakeClient.class);
    var sourceRepository = mock(GameAuthoredWorldSourceRepository.class);
    var deliveryRepository = mock(GameAuthoredWorldSourceDeliveryRepository.class);
    var owner = mock(SelectedDraftPublicationOwner.class);
    var transactions = mock(PlatformTransactionManager.class);
    var selection = fixture.operation().account().input().selection();
    var service =
        new SelectedDraftPublicationAdmissionService(
            transactions,
            account,
            freeze,
            inventory,
            world,
            sourceIntake,
            sourceRepository,
            deliveryRepository,
            owner,
            intent ->
                Optional.of(
                    AuthoredDraftPublishSelection.fromStored(
                        selection.canonicalJson(), selection.digest())),
            NAMESPACE);
    when(account.read(any()))
        .thenAnswer(
            invocation -> {
              var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
              when(evidence.request()).thenReturn(invocation.getArgument(0));
              return evidence;
            });
    var failure = Status.UNAVAILABLE.asRuntimeException();
    when(freeze.begin(any())).thenThrow(failure);

    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isSameAs(failure);
    verifyNoInteractions(inventory, world, owner, transactions);
  }

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void missingInventoryAfterAcknowledgedFreezeNeverReadsSelectorOrOpensSql() throws Exception {
    var fixture = fixture();
    var account = mock(AccountPublicationAuthorizationReadClient.class);
    when(account.read(any()))
        .thenAnswer(
            invocation -> {
              var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
              when(evidence.request()).thenReturn(invocation.getArgument(0));
              return evidence;
            });
    var inventory = mock(WorldSelectedPublicationArtifactInventoryClient.class);
    var world = mock(WorldPublishedStartLocationClient.class);
    var sourceIntake = mock(WorldAuthoredSourceIntakeClient.class);
    var sourceRepository = mock(GameAuthoredWorldSourceRepository.class);
    var deliveryRepository = mock(GameAuthoredWorldSourceDeliveryRepository.class);
    var owner = mock(SelectedDraftPublicationOwner.class);
    var transactions = mock(PlatformTransactionManager.class);
    var selection = fixture.operation().account().input().selection();
    var service =
        new SelectedDraftPublicationAdmissionService(
            transactions,
            account,
            freezeClient(fixture),
            inventory,
            world,
            sourceIntake,
            sourceRepository,
            deliveryRepository,
            owner,
            intent ->
                Optional.of(
                    AuthoredDraftPublishSelection.fromStored(
                        selection.canonicalJson(), selection.digest())),
            NAMESPACE);
    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inventory readback");
    verify(inventory).read(any());
    verifyNoInteractions(
        world, sourceIntake, sourceRepository, deliveryRepository, owner, transactions);
  }

  @Test
  void rejectsAmbientSqlTransactionBeforeEitherOwnerRead() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var service =
        service(fixture, accountClient, worldClient, mock(SelectedDraftPublicationOwner.class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient transaction");

    verifyNoInteractions(accountClient, worldClient);
  }

  @Test
  void rejectsAmbientSynchronizationBeforeEitherOwnerRead() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var service =
        service(fixture, accountClient, worldClient, mock(SelectedDraftPublicationOwner.class));
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient transaction");

    verifyNoInteractions(accountClient, worldClient);
  }

  @Test
  void rejectsChangedIntentBeforeOwnerReads() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var service =
        service(fixture, accountClient, worldClient, mock(SelectedDraftPublicationOwner.class));
    var original = fixture.intent();
    var changedIntent =
        new AuthoredDraftPublishSelection.PublishIntent(
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            original.publishRequestId(),
            original.expectedVersionStateEpoch(),
            "changed notes",
            original.selectedCommitRequestId(),
            original.selectedCommitId(),
            original.selectedCommitDigest());

    assertThatThrownBy(() -> service.admitAndReserve(changedIntent, fixture.operation().account()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected Draft intent");

    verifyNoInteractions(accountClient, worldClient);
  }

  @Test
  void accountDenialOrUnavailableReadNeverReadsWorldOrReserves() throws Exception {
    for (Status.Code code :
        new Status.Code[] {Status.Code.PERMISSION_DENIED, Status.Code.UNAVAILABLE}) {
      var fixture = fixture();
      var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
      var worldClient = mock(WorldPublishedStartLocationClient.class);
      var owner = mock(SelectedDraftPublicationOwner.class);
      var service = service(fixture, accountClient, worldClient, owner);
      var failure = Status.fromCode(code).asRuntimeException();
      when(accountClient.read(any())).thenThrow(failure);

      assertThatThrownBy(
              () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
          .isSameAs(failure);

      verify(accountClient).read(any());
      verifyNoInteractions(worldClient, owner);
    }
  }

  @Test
  void worldUnavailableAfterAccountReadNeverOpensReservation() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var owner = mock(SelectedDraftPublicationOwner.class);
    var service = service(fixture, accountClient, worldClient, owner);

    // The client is a transport double here; the mock evidence is never accepted as proof because
    // the World read fails before the reservation boundary.
    when(accountClient.read(any()))
        .thenAnswer(
            invocation -> {
              var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
              when(evidence.request()).thenReturn(invocation.getArgument(0));
              return evidence;
            });
    var failure = Status.UNAVAILABLE.asRuntimeException();
    when(worldClient.read(fixture.worldRequest())).thenThrow(failure);

    assertThatThrownBy(
            () -> service.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .isSameAs(failure);

    verify(accountClient).read(any());
    verify(worldClient).read(fixture.worldRequest());
    verifyNoInteractions(owner);
  }

  private static SelectedDraftPublicationAdmissionService service(
      Fixture fixture,
      AccountPublicationAuthorizationReadClient accountClient,
      WorldPublishedStartLocationClient worldClient,
      SelectedDraftPublicationOwner owner) {
    return new SelectedDraftPublicationAdmissionService(
        mock(PlatformTransactionManager.class),
        accountClient,
        freezeClient(fixture),
        inventoryClient(fixture),
        worldClient,
        mock(WorldAuthoredSourceIntakeClient.class),
        mock(GameAuthoredWorldSourceRepository.class),
        mock(GameAuthoredWorldSourceDeliveryRepository.class),
        owner,
        intent ->
            Optional.of(
                AuthoredDraftPublishSelection.fromStored(
                    fixture.operation().account().input().selection().canonicalJson(),
                    fixture.operation().account().input().selection().digest())),
        NAMESPACE);
  }

  private static WorldSelectedPublicationArtifactInventoryClient inventoryClient(Fixture fixture) {
    var client = mock(WorldSelectedPublicationArtifactInventoryClient.class);
    when(client.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return fixture.operation().inventory();
            });
    return client;
  }

  private static WorldSelectedDraftPublicationFreezeClient freezeClient(Fixture fixture) {
    var client = mock(WorldSelectedDraftPublicationFreezeClient.class);
    when(client.begin(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              WorldSelectedDraftPublicationFreezeEvidence.Request request =
                  invocation.getArgument(0);
              var world = fixture.worldRequest();
              var acknowledgement =
                  new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
                      request,
                      world.intakeRequestId(),
                      world.versionStateEpoch(),
                      world.publicationFence(),
                      WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN,
                      world.appliedCommitId(),
                      world.contentDigest(),
                      world.digestSchemaVersion());
              return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
                  request,
                  WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
            });
    return client;
  }

  private static SourceFixture sourceFixture(WorldPublishedStartLocationEvidence.Request selected) {
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String tenantSlug = "fixture-tenant";
    String worldSlug = "fixture-world";
    String worldDisplayName = "Fixture World";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            selected.targetNamespace(),
            registrationRequestId,
            selected.canonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName);
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            selected.targetNamespace(),
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            selected.canonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            "tenant-key",
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                selected.targetNamespace(),
                registrationRequestId,
                sourceOperationId,
                requestDigest,
                selected.canonicalTenantId(),
                tenantSlug,
                worldSlug,
                worldDisplayName,
                42L,
                "tenant-key",
                "NEW_GAME_ROW"));
    var intakeRequest =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            selected.targetNamespace(),
            selected.intakeRequestId(),
            selected.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    return new SourceFixture(source, intakeRequest);
  }

  private static WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt sourceReceipt(
      WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest request, SourceFixture fixture) {
    var source = fixture.source();
    return new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
        1,
        request.targetNamespace(),
        request.intakeRequestId(),
        UUID.nameUUIDFromBytes(
            "synthetic-world-operation".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        request.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        WorldAuthoredSourceIntakeGrpcCodec.requestDigest(fixture.intakeRequest()),
        "sha256:" + "a".repeat(64),
        source);
  }

  private static Fixture fixture() throws Exception {
    var target =
        new TargetProof(
            UUID.randomUUID(),
            UUID.randomUUID(),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    var original = IsolatedPublicationOperationFixtures.fresh(target);
    var selectionIntent = original.account().input().selection().intent();
    var intent =
        new AuthoredDraftPublishSelection.PublishIntent(
            selectionIntent.canonicalTenantId(),
            selectionIntent.canonicalVersionId(),
            selectionIntent.publishRequestId(),
            selectionIntent.expectedVersionStateEpoch(),
            selectionIntent.notes(),
            selectionIntent.selectedCommitRequestId(),
            selectionIntent.selectedCommitId(),
            selectionIntent.selectedCommitDigest());
    return new Fixture(original, intent, original.world().request());
  }

  private record Fixture(
      GameDesignPublicationOperation operation,
      AuthoredDraftPublishSelection.PublishIntent intent,
      WorldPublishedStartLocationEvidence.Request worldRequest) {}

  private record SourceFixture(
      AuthoredWorldSourceEvidence source,
      WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest intakeRequest) {}
}
