package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository.SelectionSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.PublicationEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.VisibilityFence;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class TemporalVersionPublishActivitiesImplTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");

  @Test
  void pendingReconciliationIsReturnedAsNonterminalSnapshot() {
    VersionPublishCommandServiceImpl commandService = mock(VersionPublishCommandServiceImpl.class);
    PublishWorkflowRequest request =
        new PublishWorkflowRequest(
            "tenant-1", "notes", "request-1", "publish:tenant-1:publish-request:request-1");
    when(commandService.reconcileFullVersionPublish(request))
        .thenThrow(
            new VersionPublishCommandServiceImpl.PendingReconciliationException(
                "committed publication readback could not be reconciled"));

    PublishWorkflowSnapshot snapshot =
        new TemporalVersionPublishActivitiesImpl(commandService).reconcile(request);

    assertEquals("PENDING", snapshot.status());
    assertEquals(request.publishWorkflowId(), snapshot.publishWorkflowId());
    assertEquals("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED", snapshot.failureCode());
    assertEquals(
        "committed publication readback could not be reconciled", snapshot.failureMessage());
    assertEquals(false, snapshot.isTerminal());
  }

  @Test
  void registeredActivityDeniesFreshAndPendingSelectedAndLegacyRequestsBeforeMutation() {
    assertActivityDeniesBeforeMutation(false, false);
    assertActivityDeniesBeforeMutation(false, true);
    assertActivityDeniesBeforeMutation(true, false);
    assertActivityDeniesBeforeMutation(true, true);
  }

  private void assertActivityDeniesBeforeMutation(boolean selected, boolean pending) {
    VersionRepository versions = mock(VersionRepository.class);
    GameRepository games = mock(GameRepository.class);
    PublishAttemptRepository attempts = mock(PublishAttemptRepository.class);
    AssetExportService assetExports = mock(AssetExportService.class);
    PublishAttemptService attemptService = mock(PublishAttemptService.class);
    PublishGateService gates = mock(PublishGateService.class);
    ControlPlaneDigestService controlPlane = mock(ControlPlaneDigestService.class);
    VersionAssetArtifactService artifacts = mock(VersionAssetArtifactService.class);
    PublishedReleaseBundleService bundles = mock(PublishedReleaseBundleService.class);
    RecordedParticipantDigestService recordedDigests = mock(RecordedParticipantDigestService.class);
    AuthoredDraftPublishSelectionRepository selections =
        mock(AuthoredDraftPublishSelectionRepository.class);
    VersionPublishCommandServiceImpl commandService =
        new VersionPublishCommandServiceImpl(
            versions,
            games,
            attempts,
            Mappers.getMapper(VersionMapper.class),
            assetExports,
            attemptService,
            gates,
            controlPlane,
            artifacts,
            bundles,
            recordedDigests,
            selections);

    SelectionSnapshot reservation = selected ? selectedReservation() : null;
    PublishIntent intent = selected ? reservation.selection().intent() : null;
    String publishRequestId = selected ? intent.publishRequestId() : "request-1";
    String workflowId =
        TemporalVersionPublishOrchestrator.workflowId(
            selected ? intent.canonicalTenantId().toString() : "tenant-1", publishRequestId);
    PublishWorkflowRequest request =
        new PublishWorkflowRequest("tenant-1", "notes", publishRequestId, workflowId, intent);
    if (selected) {
      when(selections.readByPublishRequest(intent.canonicalTenantId(), publishRequestId))
          .thenReturn(Optional.of(reservation));
    }
    PublishAttempt attempt = null;
    if (pending) {
      attempt = new PublishAttempt();
      attempt.setTenantId("tenant-1");
      attempt.setVersionId(10L);
      attempt.setVersionNumber(1);
      attempt.setPublishType(PublishType.FULL_VERSION);
      attempt.setPublishWorkflowId(workflowId);
      attempt.setStatus(PublishAttemptStatus.PENDING);
      attempt.setRequestDigest(selected ? reservation.selection().digest() : null);
      when(attempts.findByPublishWorkflowId(workflowId)).thenReturn(Optional.of(attempt));
    } else {
      when(attempts.findByPublishWorkflowId(workflowId)).thenReturn(Optional.empty());
    }

    assertThrows(
        IllegalStateException.class,
        () -> new TemporalVersionPublishActivitiesImpl(commandService).reconcile(request));

    verify(attempts, never()).save(any(PublishAttempt.class));
    verify(attempts, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
    verify(attemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(attemptService, never()).executeFullVersionTransaction(any());
    verify(versions, never()).save(any(Version.class));
    verify(assetExports, never()).exportAssets(any(String.class), any(Integer.class));
    verify(selections, never()).reserve(any(PublishIntent.class));
    verifyNoInteractions(
        versions,
        games,
        assetExports,
        attemptService,
        gates,
        controlPlane,
        artifacts,
        bundles,
        recordedDigests);
    if (pending) assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
  }

  private SelectionSnapshot selectedReservation() {
    UUID requestId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID commitId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    TargetProof target =
        new TargetProof(
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            10L,
            "tenant-1",
            1L,
            "tenant-1",
            "NEW_GAME_ROW");
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target,
            requestId,
            commitId,
            "base-commit-1",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.fromString("55555555-5555-4555-8555-555555555555"),
                    Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "1")));
    PublishIntent intent =
        new PublishIntent(
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            "request-1",
            "1",
            "notes",
            requestId,
            commitId,
            binding.digest());
    VisibilityFence fence =
        new VisibilityFence(
            target,
            requestId,
            commitId,
            binding.digest(),
            "[]",
            OffsetDateTime.parse("2026-10-08T00:00:00Z"));
    AuthoredDraftPublishSelection selection =
        AuthoredDraftPublishSelection.capture(
            intent, target, new PublicationEvidence(binding, fence));
    // The retained source selection is not Account permission or an authenticated World freeze.
    return new SelectionSnapshot(selection, OffsetDateTime.parse("2026-10-08T00:00:00Z"));
  }
}
