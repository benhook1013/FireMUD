package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository.SelectionSnapshot;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
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

/** Explicit internal fixtures establish plumbing only, never Account or owner authorization. */
class SelectedAuthoredDraftPublishWorkflowTest {
  @Test
  void reservationCarriesOriginalIntentWithCanonicalWorkflowAndServerResolvedRowScope() {
    Fixture f = new Fixture();
    PublishWorkflowRequest request = f.command.reserveSelection(f.intent);
    assertThat(request.intent()).isEqualTo(f.intent);
    assertThat(request.tenantId()).isEqualTo("private-tenant-key");
    assertThat(request.publishWorkflowId())
        .isEqualTo(
            TemporalVersionPublishOrchestrator.workflowId(
                f.intent.canonicalTenantId().toString(), f.intent.publishRequestId()));
    verify(f.selections).reserve(f.intent);
    verifyNoInteractions(f.versions, f.attempts, f.gate, f.exports);
  }

  @Test
  void newAttemptBindsExistingSelectedVersionAndDeniesBeforeUnreadyOwnerCarrier() {
    Fixture f = new Fixture();
    PublishWorkflowRequest request = f.command.reserveSelection(f.intent);
    Version version = new Version();
    version.setId(47L);
    version.setTenantId(request.tenantId());
    version.setVersionNumber(6);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(9L);
    when(f.versions.findByTenantIdAndIdForUpdate(request.tenantId(), 47L))
        .thenReturn(Optional.of(version));
    when(f.versions.findByTenantIdAndId(request.tenantId(), 47L)).thenReturn(Optional.of(version));
    when(f.games.findByTenantIdForUpdate(request.tenantId())).thenReturn(mock(Game.class));
    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId(request.tenantId());
    attempt.setVersionId(47L);
    attempt.setVersionNumber(6);
    attempt.setPublishWorkflowId(request.publishWorkflowId());
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setStatus(PublishAttemptStatus.PENDING);
    attempt.setRequestDigest(f.selection.digest());
    when(f.attempts.findByPublishWorkflowId(request.publishWorkflowId()))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(attempt));

    assertThatThrownBy(() -> f.command.reconcileFullVersionPublish(request))
        .hasMessageContaining("PUBLISH_OWNER_CARRIER_UNAVAILABLE");
    verify(f.attemptService)
        .createFullVersionAttempt(
            Mappers.getMapper(VersionMapper.class).toDto(version),
            request.publishWorkflowId(),
            f.selection.digest());
    verify(f.versions, never()).save(any());
    verify(f.versions, never()).findTopByTenantIdOrderByVersionNumberDesc(any());
    verifyNoInteractions(f.gate, f.exports);
  }

  @Test
  void changedNotesConflictBeforeAttemptOrContentWrites() {
    Fixture f = new Fixture();
    PublishWorkflowRequest original = f.command.reserveSelection(f.intent);
    PublishWorkflowRequest changed =
        new PublishWorkflowRequest(
            original.tenantId(),
            "changed",
            original.publishRequestId(),
            original.publishWorkflowId(),
            original.intent());
    assertThatThrownBy(() -> f.command.reconcileFullVersionPublish(changed))
        .hasMessageContaining("PUBLISH_ATTEMPT_IDENTITY_CONFLICT");
    verifyNoInteractions(f.versions, f.attempts, f.gate, f.exports);
  }

  private static final class Fixture {
    final PublishIntent intent =
        new PublishIntent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "stable-request",
            "9",
            "original notes",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64));
    final AuthoredDraftPublishSelection selection = mock(AuthoredDraftPublishSelection.class);
    final AuthoredDraftPublishSelectionRepository selections =
        mock(AuthoredDraftPublishSelectionRepository.class);
    final VersionRepository versions = mock(VersionRepository.class);
    final GameRepository games = mock(GameRepository.class);
    final PublishAttemptRepository attempts = mock(PublishAttemptRepository.class);
    final PublishAttemptService attemptService = mock(PublishAttemptService.class);
    final PublishGateService gate = mock(PublishGateService.class);
    final AssetExportService exports = mock(AssetExportService.class);
    final VersionPublishCommandServiceImpl command;

    Fixture() {
      when(selection.intent()).thenReturn(intent);
      when(selection.target())
          .thenReturn(
              new TargetProof(
                  intent.canonicalTenantId(),
                  intent.canonicalVersionId(),
                  47L,
                  "private-tenant-key",
                  8L,
                  "private-tenant-key",
                  "NEW_GAME_ROW"));
      when(selection.digest()).thenReturn("sha256:" + "b".repeat(64));
      SelectionSnapshot snapshot = new SelectionSnapshot(selection, OffsetDateTime.now());
      when(selections.reserve(intent)).thenReturn(snapshot);
      when(selections.readByPublishRequest(intent.canonicalTenantId(), intent.publishRequestId()))
          .thenReturn(Optional.of(snapshot));
      when(attemptService.executeFullVersionTransaction(any()))
          .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
      PublishedReleaseBundleService bundles = mock(PublishedReleaseBundleService.class);
      when(bundles.findPublishedReleaseBundle(any(), any(Long.class))).thenReturn(Optional.empty());
      VersionAssetArtifactService artifacts = mock(VersionAssetArtifactService.class);
      when(artifacts.findState(any(), any(Long.class))).thenReturn(Optional.empty());
      command =
          new VersionPublishCommandServiceImpl(
              versions,
              games,
              attempts,
              Mappers.getMapper(VersionMapper.class),
              exports,
              attemptService,
              gate,
              mock(ControlPlaneDigestService.class),
              artifacts,
              bundles,
              mock(RecordedParticipantDigestService.class),
              selections);
    }
  }
}
