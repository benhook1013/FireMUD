package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit proof for the admission ordering and exact preflight checks; clients are transport doubles.
 */
class SelectedDraftPublicationAdmissionServiceTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void rejectsAmbientSqlTransactionBeforeEitherOwnerRead() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var service = service(accountClient, worldClient, mock(SelectedDraftPublicationOwner.class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                service.admitAndReserve(
                    fixture.intent(), fixture.operation().account(), fixture.worldRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient transaction");

    verifyNoInteractions(accountClient, worldClient);
  }

  @Test
  void rejectsAmbientSynchronizationBeforeEitherOwnerRead() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var service = service(accountClient, worldClient, mock(SelectedDraftPublicationOwner.class));
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(
            () ->
                service.admitAndReserve(
                    fixture.intent(), fixture.operation().account(), fixture.worldRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient transaction");

    verifyNoInteractions(accountClient, worldClient);
  }

  @Test
  void rejectsChangedIntentAndWorldRequestIdentityBeforeOwnerReads() throws Exception {
    var fixture = fixture();
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    var worldClient = mock(WorldPublishedStartLocationClient.class);
    var service = service(accountClient, worldClient, mock(SelectedDraftPublicationOwner.class));
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

    assertThatThrownBy(
            () ->
                service.admitAndReserve(
                    changedIntent, fixture.operation().account(), fixture.worldRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected Draft intent");
    assertThatThrownBy(
            () ->
                service.admitAndReserve(
                    fixture.intent(),
                    fixture.operation().account(),
                    withPublicationRequestId(fixture.worldRequest(), "different-request")))
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
      var service = service(accountClient, worldClient, owner);
      var failure = Status.fromCode(code).asRuntimeException();
      when(accountClient.read(any())).thenThrow(failure);

      assertThatThrownBy(
              () ->
                  service.admitAndReserve(
                      fixture.intent(), fixture.operation().account(), fixture.worldRequest()))
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
    var service = service(accountClient, worldClient, owner);

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
            () ->
                service.admitAndReserve(
                    fixture.intent(), fixture.operation().account(), fixture.worldRequest()))
        .isSameAs(failure);

    verify(accountClient).read(any());
    verify(worldClient).read(fixture.worldRequest());
    verifyNoInteractions(owner);
  }

  private static SelectedDraftPublicationAdmissionService service(
      AccountPublicationAuthorizationReadClient accountClient,
      WorldPublishedStartLocationClient worldClient,
      SelectedDraftPublicationOwner owner) {
    return new SelectedDraftPublicationAdmissionService(
        mock(PlatformTransactionManager.class), accountClient, worldClient, owner, NAMESPACE);
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

  private static WorldPublishedStartLocationEvidence.Request withPublicationRequestId(
      WorldPublishedStartLocationEvidence.Request request, String publicationRequestId) {
    return new WorldPublishedStartLocationEvidence.Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        publicationRequestId,
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        request.worldAffectedTuples());
  }

  private record Fixture(
      GameDesignPublicationOperation operation,
      AuthoredDraftPublishSelection.PublishIntent intent,
      WorldPublishedStartLocationEvidence.Request worldRequest) {}
}
