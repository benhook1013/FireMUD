package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SelectedDraftPublicationCommandServiceTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void preselectedDraftAdmissionCompletesBeforeSelectedExistingVersionFinalizer() throws Exception {
    var fixture = fixture();
    var admission = mock(SelectedDraftPublicationAdmissionService.class);
    var reader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var finalizer = mock(VersionPublishCommandServiceImpl.class);
    var reservation = mock(SelectedDraftPublicationOwner.Reservation.class);
    var result = mock(VersionDto.class);
    when(reader.read(fixture.intent(), fixture.operation().account()))
        .thenReturn(selectionOnly(fixture));
    when(admission.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .thenReturn(reservation);
    when(reservation.selection()).thenReturn(fixture.selection());
    when(reservation.operation()).thenReturn(fixture.operation());
    when(finalizer.publishSelectedDraftFullVersion(
            fixture.selection().target().gameDesignVersionTenantKey(),
            fixture.selection().target().gameDesignVersionRowId(),
            fixture.intent().notes(),
            fixture.intent().publishRequestId(),
            fixture.operation().workflowId()))
        .thenReturn(result);
    var service = service(admission, reader, finalizer);

    var actual =
        service.publishSelectedDraftFullVersion(fixture.intent(), fixture.operation().account());

    org.assertj.core.api.Assertions.assertThat(actual).isSameAs(result);
    var publicationOrder = inOrder(admission, finalizer);
    publicationOrder
        .verify(admission)
        .admitAndReserve(fixture.intent(), fixture.operation().account());
    publicationOrder
        .verify(finalizer)
        .publishSelectedDraftFullVersion(
            fixture.selection().target().gameDesignVersionTenantKey(),
            fixture.selection().target().gameDesignVersionRowId(),
            fixture.intent().notes(),
            fixture.intent().publishRequestId(),
            fixture.operation().workflowId());
    verify(finalizer, never())
        .publishFullVersion(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void exactSettledRetryUsesStoredIdentityWithoutFreshHeldReadOrReservation() throws Exception {
    var fixture = fixture();
    var admission = mock(SelectedDraftPublicationAdmissionService.class);
    var reader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var finalizer = mock(VersionPublishCommandServiceImpl.class);
    var result = mock(VersionDto.class);
    when(reader.read(fixture.intent(), fixture.operation().account()))
        .thenReturn(state(fixture, "PUBLISHED"));
    when(finalizer.publishSelectedDraftFullVersion(
            fixture.selection().target().gameDesignVersionTenantKey(),
            fixture.selection().target().gameDesignVersionRowId(),
            fixture.intent().notes(),
            fixture.intent().publishRequestId(),
            fixture.operation().workflowId()))
        .thenReturn(result);
    var service = service(admission, reader, finalizer);

    var actual =
        service.publishSelectedDraftFullVersion(fixture.intent(), fixture.operation().account());

    org.assertj.core.api.Assertions.assertThat(actual).isSameAs(result);
    verify(admission, never()).admitAndReserve(any(), any());
    verify(finalizer)
        .publishSelectedDraftFullVersion(
            fixture.selection().target().gameDesignVersionTenantKey(),
            fixture.selection().target().gameDesignVersionRowId(),
            fixture.intent().notes(),
            fixture.intent().publishRequestId(),
            fixture.operation().workflowId());
    verify(finalizer, never())
        .publishFullVersion(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void admissionDenialAndPartialLocalIdentityNeverReachFinalizer() throws Exception {
    var fixture = fixture();
    var denial = new IllegalStateException("Account publication authorization denied");
    var admission = mock(SelectedDraftPublicationAdmissionService.class);
    var reader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var finalizer = mock(VersionPublishCommandServiceImpl.class);
    when(reader.read(fixture.intent(), fixture.operation().account()))
        .thenReturn(selectionOnly(fixture));
    when(admission.admitAndReserve(fixture.intent(), fixture.operation().account()))
        .thenThrow(denial);

    assertThatThrownBy(
            () ->
                service(admission, reader, finalizer)
                    .publishSelectedDraftFullVersion(
                        fixture.intent(), fixture.operation().account()))
        .isSameAs(denial);
    verifyNoInteractions(finalizer);

    var partialAdmission = mock(SelectedDraftPublicationAdmissionService.class);
    var partialReader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var partialFinalizer = mock(VersionPublishCommandServiceImpl.class);
    when(partialReader.read(fixture.intent(), fixture.operation().account()))
        .thenReturn(
            new SelectedDraftPublicationCommandService.DurableState(
                Optional.empty(),
                Optional.of(
                    new GameDesignPublicationOperationRepository.Readback(
                        fixture.operation(), "PENDING", new byte[] {1}))));

    assertThatThrownBy(
            () ->
                service(partialAdmission, partialReader, partialFinalizer)
                    .publishSelectedDraftFullVersion(
                        fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_IDENTITY_INCOMPLETE");
    verifyNoInteractions(partialAdmission, partialFinalizer);
  }

  @Test
  void missingSelectionFailsClosedBeforeAccountOrWorldAdmission() throws Exception {
    var fixture = fixture();
    var admission = mock(SelectedDraftPublicationAdmissionService.class);
    var reader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var finalizer = mock(VersionPublishCommandServiceImpl.class);
    when(reader.read(fixture.intent(), fixture.operation().account()))
        .thenReturn(SelectedDraftPublicationCommandService.DurableState.absent());

    assertThatThrownBy(
            () ->
                service(admission, reader, finalizer)
                    .publishSelectedDraftFullVersion(
                        fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTION_UNAVAILABLE");

    verifyNoInteractions(admission, finalizer);
  }

  @Test
  void exactRetryRejectsChangedIntentOrAccountBinding() throws Exception {
    var fixture = fixture();
    var alteredIntent = withNotes(fixture.intent(), "substituted notes");
    assertRejectedRetry(fixture, alteredIntent, fixture.operation().account());

    var changedAccount =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            fixture.operation().account().fenceId(),
            fixture.operation().account().input(),
            fixture.operation().account().sources());
    assertRejectedRetry(fixture, fixture.intent(), changedAccount);
  }

  @Test
  void compositionRejectsAmbientTransactionBeforeLocalReadsAdmissionOrFinalizer() throws Exception {
    var fixture = fixture();
    var admission = mock(SelectedDraftPublicationAdmissionService.class);
    var reader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var finalizer = mock(VersionPublishCommandServiceImpl.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                service(admission, reader, finalizer)
                    .publishSelectedDraftFullVersion(
                        fixture.intent(), fixture.operation().account()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient SQL transaction");

    verifyNoInteractions(reader, admission, finalizer);
  }

  private static void assertRejectedRetry(
      Fixture fixture, PublishIntent intent, AccountPublicationAuthorizationBinding account) {
    var admission = mock(SelectedDraftPublicationAdmissionService.class);
    var reader = mock(SelectedDraftPublicationCommandService.DurableStateReader.class);
    var finalizer = mock(VersionPublishCommandServiceImpl.class);
    when(reader.read(any(), any())).thenReturn(state(fixture, "PENDING"));

    assertThatThrownBy(
            () ->
                service(admission, reader, finalizer)
                    .publishSelectedDraftFullVersion(intent, account))
        .isInstanceOf(RuntimeException.class);

    verifyNoInteractions(admission, finalizer);
  }

  private static SelectedDraftPublicationCommandService service(
      SelectedDraftPublicationAdmissionService admission,
      SelectedDraftPublicationCommandService.DurableStateReader reader,
      VersionPublishCommandServiceImpl finalizer) {
    return new SelectedDraftPublicationCommandService(admission, reader, finalizer);
  }

  private static SelectedDraftPublicationCommandService.DurableState state(
      Fixture fixture, String outcome) {
    return new SelectedDraftPublicationCommandService.DurableState(
        Optional.of(fixture.selection()),
        Optional.of(
            new GameDesignPublicationOperationRepository.Readback(
                fixture.operation(), outcome, new byte[] {1})));
  }

  private static SelectedDraftPublicationCommandService.DurableState selectionOnly(
      Fixture fixture) {
    return new SelectedDraftPublicationCommandService.DurableState(
        Optional.of(fixture.selection()), Optional.empty());
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
    var operation = IsolatedPublicationOperationFixtures.fresh(target);
    var binding = operation.account().input().selection();
    var bindingIntent = binding.intent();
    var intent =
        new PublishIntent(
            bindingIntent.canonicalTenantId(),
            bindingIntent.canonicalVersionId(),
            bindingIntent.publishRequestId(),
            bindingIntent.expectedVersionStateEpoch(),
            bindingIntent.notes(),
            bindingIntent.selectedCommitRequestId(),
            bindingIntent.selectedCommitId(),
            bindingIntent.selectedCommitDigest());
    var selection =
        AuthoredDraftPublishSelection.fromStored(binding.canonicalJson(), binding.digest());
    return new Fixture(operation, selection, intent);
  }

  private static PublishIntent withNotes(PublishIntent intent, String notes) {
    return new PublishIntent(
        intent.canonicalTenantId(),
        intent.canonicalVersionId(),
        intent.publishRequestId(),
        intent.expectedVersionStateEpoch(),
        notes,
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest());
  }

  private record Fixture(
      GameDesignPublicationOperation operation,
      AuthoredDraftPublishSelection selection,
      PublishIntent intent) {}
}
