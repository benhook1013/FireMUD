package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PluginVersionStatusEventDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedPluginVersionDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PluginVersionStatusEventRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedPluginVersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.ParsedPluginBundle;
import net.firedevops.firemud.gamedesign.service.PluginBundleIntakeService;
import net.firedevops.firemud.gamedesign.service.PluginBundleStorageService;
import net.firedevops.firemud.gamedesign.service.PluginDistributionManifest;
import net.firedevops.firemud.gamedesign.service.PublishAttemptPendingReconciliationException;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import net.firedevops.firemud.gamedesign.service.ScriptPatchPublishFailureException;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

class VersionServiceImplTest {
  private static final String PUBLISH_REQUEST_ID = "publish-request-1";
  private static final String RETRYABLE_PARTICIPANT_DEPENDENCY_FAILURE_CODE =
      "PARTICIPANT_DEPENDENCY_UNAVAILABLE_RETRYABLE";

  @Mock private VersionRepository versionRepository;
  @Mock private GameRepository gameRepository;
  @Mock private PublishedPluginVersionRepository publishedPluginVersionRepository;
  @Mock private PluginVersionStatusEventRepository pluginVersionStatusEventRepository;
  @Mock private AutomationScriptingClient scriptingClient;
  @Mock private AssetExportService assetExportService;
  @Mock private PublishAttemptService publishAttemptService;
  @Mock private PublishGateService publishGateService;
  @Mock private ControlPlaneDigestService controlPlaneDigestService;
  @Mock private VersionAssetArtifactService versionAssetArtifactService;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;
  @Mock private RecordedParticipantDigestService recordedParticipantDigestService;
  @Mock private PluginBundleIntakeService pluginBundleIntakeService;
  @Mock private PluginBundleStorageService pluginBundleStorageService;
  @Mock private VersionPublishCommandServiceImpl publishCommandService;
  @Mock private TemporalVersionPublishOrchestrator temporalPublishOrchestrator;

  private VersionServiceImpl service;

  @BeforeEach
  void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    VersionMapper mapper = Mappers.getMapper(VersionMapper.class);
    when(publishedPluginVersionRepository.save(any(PublishedPluginVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publishAttemptService.findByPublishWorkflowId(any(String.class)))
        .thenReturn(Optional.empty());
    doAnswer(
            invocation -> {
              try {
                return ((Supplier<?>) invocation.getArgument(0)).get();
              } catch (RuntimeException ex) {
                throw new PublishAttemptService.ScriptPatchTransactionException(ex);
              }
            })
        .when(publishAttemptService)
        .executeScriptPatchTransaction(any());
    service =
        new VersionServiceImpl(
            versionRepository,
            gameRepository,
            publishedPluginVersionRepository,
            pluginVersionStatusEventRepository,
            mapper,
            publishAttemptService,
            publishGateService,
            controlPlaneDigestService,
            versionAssetArtifactService,
            publishedReleaseBundleService,
            recordedParticipantDigestService,
            pluginBundleIntakeService,
            pluginBundleStorageService,
            publishCommandService,
            Optional.empty());
  }

  @Test
  void publishVersionWithoutDurableWorkflowDoesNotMutate() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.publishVersion("tenant-1", "notes", PUBLISH_REQUEST_ID));

    assertTrue(thrown.getMessage().startsWith("PUBLISH_WORKFLOW_UNAVAILABLE"));
    verify(publishCommandService, org.mockito.Mockito.never())
        .publishFullVersion(any(), any(), any(), any());
  }

  @Test
  void scriptPatchMissingManifestAfterParticipantGatesFailsAndReplaysTypedFailure() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version latest = new Version();
    latest.setId(9L);
    latest.setTenantId("tenant-1");
    latest.setVersionNumber(7);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.of(latest));

    Version savedDraft = new Version();
    savedDraft.setId(11L);
    savedDraft.setTenantId("tenant-1");
    savedDraft.setVersionNumber(8);
    savedDraft.setScriptPatchVersion("patch-2");
    savedDraft.setNotes("notes");
    savedDraft.setVersionState(VersionLifecycleState.DRAFT);
    savedDraft.setVersionStateEpoch(1L);
    savedDraft.setBaseVersionId(3L);
    savedDraft.setScriptOnly(true);
    savedDraft.setUpdatedAt(java.time.LocalDateTime.now());
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt pendingAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    PublishAttempt failedAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.FAILED, 11L, 8, 3L);
    failedAttempt.setFailureCode("SCRIPT_PATCH_MANIFEST_UNAVAILABLE");
    failedAttempt.setFailureMessage(
        "SCRIPT_PATCH_MANIFEST_UNAVAILABLE: the reserved patch has no owner-verified affected-script manifest");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(pendingAttempt), Optional.of(failedAttempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L))
        .thenReturn(Optional.of(savedDraft));
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(List.of());

    ScriptPatchPublishFailureException firstFailure =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals("SCRIPT_PATCH_MANIFEST_UNAVAILABLE", firstFailure.failureCode());
    verify(versionRepository, times(1)).save(any(Version.class));
    verify(publishAttemptService)
        .createScriptPatchAttempt(
            any(VersionDto.class),
            org.mockito.ArgumentMatchers.eq(
                "publish-script-patch:tenant-1:publish-request:" + PUBLISH_REQUEST_ID),
            org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq(
                PublicationDigestRequestBinding.patch(
                        "tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID)
                    .requestDigest()));
    verify(publishAttemptService)
        .markScriptPatchFailed(
            org.mockito.ArgumentMatchers.eq(binding.derivedWorkflowIdentity()),
            org.mockito.ArgumentMatchers.eq("SCRIPT_PATCH_MANIFEST_UNAVAILABLE"),
            org.mockito.ArgumentMatchers.contains("owner-verified affected-script manifest"));
    verify(versionRepository).delete(savedDraft);
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchSucceeded(any(String.class));
    ScriptPatchPublishFailureException replay =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "different notes", PUBLISH_REQUEST_ID));

    assertEquals(firstFailure.failureCode(), replay.failureCode());
    assertEquals(firstFailure.getMessage(), replay.getMessage());
    verify(publishAttemptService, times(1))
        .createScriptPatchAttempt(any(), any(String.class), any(Long.class), any(String.class));
    verify(publishGateService, times(1))
        .collectScriptPatchParticipantDigests(any(), any(String.class), any(String.class));
  }

  @Test
  void unsupportedAutomationDigestScopeBlocksScriptPatchBeforeNotification() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version latest = new Version();
    latest.setId(9L);
    latest.setTenantId("tenant-1");
    latest.setVersionNumber(7);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.of(latest));

    Version savedDraft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt pendingAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(pendingAttempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L))
        .thenReturn(Optional.of(savedDraft));
    when(scriptingClient.getDraftDesignDigestForScriptPatch(
            any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "patch-2",
                3L,
                null,
                null,
                null,
                "UNSUPPORTED_SCOPE",
                "script-patch digest scope is unsupported"));
    when(controlPlaneDigestService.getDigestForScriptPatch(any(VersionDto.class)))
        .thenReturn(
            new DesignControlPlaneDigestDto(
                "tenant-1", "patch-2", "commit-1", "control-plane-digest", 1));

    PublishGateServiceImpl realPublishGate =
        new PublishGateServiceImpl(
            controlPlaneDigestService,
            org.mockito.Mockito.mock(WorldManagementClient.class),
            org.mockito.Mockito.mock(EntityManagementClient.class),
            org.mockito.Mockito.mock(GameLogicClient.class),
            scriptingClient);
    VersionServiceImpl composedService = serviceWithPublishGate(realPublishGate);

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                composedService.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishGateFailureCode.PARTICIPANT_UNAVAILABLE, thrown.failureCode());
    assertEquals("UNSUPPORTED_SCOPE", thrown.participantFailureCode());
    String workflowId = "publish-script-patch:tenant-1:publish-request:" + PUBLISH_REQUEST_ID;
    ArgumentCaptor<PublicationDigestRequestBinding> bindingCaptor =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(scriptingClient).getDraftDesignDigestForScriptPatch(bindingCaptor.capture());
    PublicationDigestRequestBinding observedBinding = bindingCaptor.getValue();
    assertEquals(binding.tenantId(), observedBinding.tenantId());
    assertEquals(binding.scopeKind(), observedBinding.scopeKind());
    assertEquals(binding.baseVersionId(), observedBinding.baseVersionId());
    assertEquals(binding.scriptPatchVersion(), observedBinding.scriptPatchVersion());
    assertEquals(binding.publishRequestId(), observedBinding.publishRequestId());
    assertEquals(binding.derivedWorkflowIdentity(), observedBinding.derivedWorkflowIdentity());
    assertEquals(binding.requestDigest(), observedBinding.requestDigest());
    verify(scriptingClient, org.mockito.Mockito.never())
        .notifyScriptVersionUpdate("tenant-1", 3L, "patch-2", List.of());
    verify(publishAttemptService)
        .markScriptPatchFailed(
            org.mockito.ArgumentMatchers.eq(workflowId),
            org.mockito.ArgumentMatchers.eq(PublishGateFailureCode.PARTICIPANT_UNAVAILABLE.name()),
            org.mockito.ArgumentMatchers.contains("script-patch digest scope is unsupported"));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchSucceeded(any(String.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .recordScriptPatchParticipantDigests(any(String.class), any(List.class));
    verify(versionRepository, times(1)).save(any(Version.class));
    verify(versionRepository).delete(savedDraft);
    verify(recordedParticipantDigestService, org.mockito.Mockito.never())
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));
  }

  @Test
  void concurrentDifferentFailedReceiptOverridesCurrentParticipantFailure() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    Version latest = new Version();
    latest.setId(9L);
    latest.setTenantId("tenant-1");
    latest.setVersionNumber(7);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.of(latest));
    when(versionRepository.save(any(Version.class)))
        .thenReturn(scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes"));

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt concurrentFailure =
        scriptPatchAttempt(binding, PublishAttemptStatus.FAILED, 11L, 8, 3L);
    concurrentFailure.setFailureCode("SCRIPT_PATCH_MANIFEST_UNAVAILABLE");
    concurrentFailure.setFailureMessage("stored manifest failure");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(concurrentFailure));
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.PARTICIPANT_UNAVAILABLE,
                "participant observation failed",
                "UNSUPPORTED_SCOPE"));

    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals("SCRIPT_PATCH_MANIFEST_UNAVAILABLE", thrown.failureCode());
    assertEquals("stored manifest failure", thrown.getMessage());
    verify(versionRepository, org.mockito.Mockito.never()).delete(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
  }

  @Test
  void sameStableScriptPatchIdReplaysSucceededAttemptWithoutAllocatingVersion() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.SUCCEEDED, 11L, 8, 3L);
    Version published =
        scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "first notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(published));

    VersionDto replay =
        service.publishScriptPatchVersion(
            "tenant-1", 3L, "patch-2", "different notes", PUBLISH_REQUEST_ID);

    assertEquals(11L, replay.id());
    assertEquals("first notes", replay.notes());
    assertEquals(VersionLifecycleState.PUBLISHED, replay.versionState());
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
    verify(publishGateService, org.mockito.Mockito.never())
        .collectScriptPatchParticipantDigests(any(), any(), any());
  }

  @Test
  void pendingScriptPatchRetryReplaysConcurrentSuccessWithoutDispatch() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "first notes");
    Version published =
        scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "first notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L))
        .thenReturn(Optional.of(draft), Optional.of(published));

    ScriptPatchPublishFailureException pending =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishAttemptPendingReconciliationException.ERROR_CODE, pending.failureCode());
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());

    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    VersionDto result =
        service.publishScriptPatchVersion("tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID);

    assertEquals(11L, result.id());
    assertEquals(VersionLifecycleState.PUBLISHED, result.versionState());
    verify(publishGateService, org.mockito.Mockito.never())
        .collectScriptPatchParticipantDigests(any(), any(), any());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
  }

  @Test
  void sameStableScriptPatchIdReplaysSucceededAttemptAfterActivation() {
    assertSucceededScriptPatchReplayForLifecycle(VersionLifecycleState.ACTIVE);
  }

  @Test
  void sameStableScriptPatchIdReplaysSucceededAttemptAfterRetirement() {
    assertSucceededScriptPatchReplayForLifecycle(VersionLifecycleState.RETIRED);
  }

  @Test
  void sameStableScriptPatchIdRejectsSucceededDraftWithoutAllocatingVersion() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.SUCCEEDED, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "first notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.publishScriptPatchVersion(
                "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  private void assertSucceededScriptPatchReplayForLifecycle(VersionLifecycleState lifecycleState) {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.SUCCEEDED, 11L, 8, 3L);
    Version version = scriptPatchVersion(11L, 8, 3L, lifecycleState, "first notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(version));

    VersionDto replay =
        service.publishScriptPatchVersion(
            "tenant-1", 3L, "patch-2", "different notes", PUBLISH_REQUEST_ID);

    assertEquals(11L, replay.id());
    assertEquals(lifecycleState, replay.versionState());
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  @Test
  void sameStableScriptPatchIdKeepsPriorPendingAttemptForReconciliation() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "first notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));

    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "different notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishAttemptPendingReconciliationException.ERROR_CODE, thrown.failureCode());
    assertTrue(thrown.getCause().getMessage().contains("SCRIPT_PATCH_MANIFEST_UNAVAILABLE"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    assertEquals(VersionLifecycleState.DRAFT, draft.getVersionState());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, org.mockito.Mockito.never()).delete(any(Version.class));
    verify(publishGateService, org.mockito.Mockito.never())
        .collectScriptPatchParticipantDigests(any(), any(String.class), any(String.class));
  }

  @Test
  void failedScriptPatchRecordsStableAttemptAndRemovesDraftForRetry() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version latest = new Version();
    latest.setId(9L);
    latest.setTenantId("tenant-1");
    latest.setVersionNumber(7);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.of(latest));

    Version savedDraft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt pendingAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(pendingAttempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L))
        .thenReturn(Optional.of(savedDraft));
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH, "scope mismatch"));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH, thrown.failureCode());
    String workflowId = "publish-script-patch:tenant-1:publish-request:" + PUBLISH_REQUEST_ID;
    verify(publishAttemptService)
        .createScriptPatchAttempt(
            any(VersionDto.class),
            org.mockito.ArgumentMatchers.eq(workflowId),
            org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq(
                PublicationDigestRequestBinding.patch(
                        "tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID)
                    .requestDigest()));
    verify(publishAttemptService)
        .markScriptPatchFailed(
            org.mockito.ArgumentMatchers.eq(workflowId),
            org.mockito.ArgumentMatchers.eq(
                PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH.name()),
            org.mockito.ArgumentMatchers.eq("scope mismatch"));
    verify(versionRepository).delete(savedDraft);
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchSucceeded(any(String.class));
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.Code.class,
      names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void transientParticipantReadCleansAndAllowsNewScriptPatchRequest(Status.Code statusCode) {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    Version retriedDraft = scriptPatchVersion(12L, 9, 3L, VersionLifecycleState.DRAFT, "retry");
    PublishAttempt pendingAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    PublishAttempt failedAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.FAILED, 11L, 8, 3L);
    StatusRuntimeException participantFailure =
        new StatusRuntimeException(Status.fromCode(statusCode));
    String failureMessage =
        RETRYABLE_PARTICIPANT_DEPENDENCY_FAILURE_CODE
            + ": participant digest gating encountered a transient dependency failure before notification; exact retries replay this failed receipt, so retry with a new publish request ID after the dependency recovers";
    failedAttempt.setFailureCode(RETRYABLE_PARTICIPANT_DEPENDENCY_FAILURE_CODE);
    failedAttempt.setFailureMessage(failureMessage);
    AtomicBoolean tupleReserved = new AtomicBoolean();
    AtomicInteger allocations = new AtomicInteger();
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.empty(), Optional.empty());
    when(versionRepository.save(any(Version.class)))
        .thenAnswer(
            invocation -> {
              tupleReserved.set(true);
              return allocations.incrementAndGet() == 1 ? draft : retriedDraft;
            });
    when(versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
            "tenant-1", 3L, "patch-2"))
        .thenAnswer(invocation -> tupleReserved.get() ? List.of(draft) : List.of());
    doAnswer(
            invocation -> {
              tupleReserved.set(false);
              return null;
            })
        .when(versionRepository)
        .delete(draft);
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(pendingAttempt), Optional.of(failedAttempt));
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenThrow(participantFailure, new PublishAttemptPendingReconciliationException());

    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(RETRYABLE_PARTICIPANT_DEPENDENCY_FAILURE_CODE, thrown.failureCode());
    assertEquals(failureMessage, thrown.getMessage());
    assertFalse(tupleReserved.get());
    verify(publishAttemptService)
        .markScriptPatchFailed(
            binding.derivedWorkflowIdentity(),
            RETRYABLE_PARTICIPANT_DEPENDENCY_FAILURE_CODE,
            failureMessage);
    verify(versionRepository).delete(draft);
    ScriptPatchPublishFailureException exactReplay =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "changed notes", PUBLISH_REQUEST_ID));
    assertEquals(RETRYABLE_PARTICIPANT_DEPENDENCY_FAILURE_CODE, exactReplay.failureCode());
    assertEquals(failureMessage, exactReplay.getMessage());

    PublicationDigestRequestBinding newBinding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", "publish-request-2");
    PublishAttemptPendingReconciliationException newRequestResult =
        assertThrows(
            PublishAttemptPendingReconciliationException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "retry", "publish-request-2"));
    assertEquals(
        PublishAttemptPendingReconciliationException.ERROR_CODE, newRequestResult.errorCode());
    assertTrue(tupleReserved.get());
    verify(versionRepository, times(2))
        .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
            "tenant-1", 3L, "patch-2");
    verify(versionRepository, times(2)).save(any(Version.class));
    ArgumentCaptor<String> workflowIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(publishAttemptService, times(2))
        .createScriptPatchAttempt(
            any(), workflowIdCaptor.capture(), any(Long.class), any(String.class));
    assertEquals(
        List.of(binding.derivedWorkflowIdentity(), newBinding.derivedWorkflowIdentity()),
        workflowIdCaptor.getAllValues());
    verify(publishGateService, times(2))
        .collectScriptPatchParticipantDigests(any(), any(String.class), any(String.class));
  }

  @Test
  void pendingNonDraftCandidateRemainsHeldForReconciliation() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version published = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(published));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertTrue(thrown.getMessage().startsWith("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED:"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    assertEquals(VersionLifecycleState.PUBLISHED, published.getVersionState());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, org.mockito.Mockito.never()).delete(any(Version.class));
    verify(publishGateService, org.mockito.Mockito.never())
        .collectScriptPatchParticipantDigests(any(), any(String.class), any(String.class));
  }

  @Test
  void ambiguousManifestFailureCleanupTransactionRemainsPendingForExactRetry() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.empty());
    when(versionRepository.save(any(Version.class))).thenReturn(draft);
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(List.of());

    AtomicInteger transactionCalls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (transactionCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("transaction completion outcome is ambiguous");
              }
              return ((Supplier<?>) invocation.getArgument(0)).get();
            })
        .when(publishAttemptService)
        .executeScriptPatchTransaction(any());

    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishAttemptPendingReconciliationException.ERROR_CODE, thrown.failureCode());
    assertTrue(
        thrown
            .getMessage()
            .startsWith(PublishAttemptPendingReconciliationException.ERROR_CODE + ":"));
    assertEquals("transaction completion outcome is ambiguous", thrown.getCause().getMessage());
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, org.mockito.Mockito.never()).delete(any(Version.class));

    ScriptPatchPublishFailureException retryFailure =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(
        PublishAttemptPendingReconciliationException.ERROR_CODE, retryFailure.failureCode());
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
  }

  @Test
  void manifestUnavailableAfterPassingParticipantGatesFailsAndCleansCandidate() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.empty());
    when(versionRepository.save(any(Version.class))).thenReturn(draft);
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(List.of());
    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals("SCRIPT_PATCH_MANIFEST_UNAVAILABLE", thrown.failureCode());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchSucceeded(any(String.class));
    verify(publishAttemptService)
        .markScriptPatchFailed(
            org.mockito.ArgumentMatchers.eq(binding.derivedWorkflowIdentity()),
            org.mockito.ArgumentMatchers.eq("SCRIPT_PATCH_MANIFEST_UNAVAILABLE"),
            org.mockito.ArgumentMatchers.contains("owner-verified affected-script manifest"));
    verify(versionRepository).delete(draft);
  }

  @Test
  void legacyPendingAttemptWithPossibleDispatchRemainsHeldForReconciliation() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertTrue(thrown.getMessage().contains("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED"));
    assertTrue(thrown.getCause().getMessage().contains("SCRIPT_PATCH_MANIFEST_UNAVAILABLE"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, org.mockito.Mockito.never()).delete(any(Version.class));
  }

  @Test
  void pendingAttemptRemainsHeldWithoutRedispatchOrCleanup() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));
    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishAttemptPendingReconciliationException.ERROR_CODE, thrown.failureCode());
    assertTrue(thrown.getCause().getMessage().contains("SCRIPT_PATCH_MANIFEST_UNAVAILABLE"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    assertEquals(VersionLifecycleState.DRAFT, draft.getVersionState());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, org.mockito.Mockito.never()).delete(any(Version.class));
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
  }

  @Test
  void manifestFailureReceiptWriteErrorRetainsPendingForExactRetry() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version latest = new Version();
    latest.setId(9L);
    latest.setTenantId("tenant-1");
    latest.setVersionNumber(7);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.of(latest));

    Version draft = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.DRAFT, "notes");
    when(versionRepository.save(any(Version.class))).thenReturn(draft);
    when(versionRepository.findByTenantIdAndId("tenant-1", 11L)).thenReturn(Optional.of(draft));
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt pendingAttempt =
        scriptPatchAttempt(binding, PublishAttemptStatus.PENDING, 11L, 8, 3L);
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.empty(), Optional.of(pendingAttempt), Optional.of(pendingAttempt));
    when(publishGateService.collectScriptPatchParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(List.of());
    doThrow(new IllegalStateException("failure receipt write failed"))
        .when(publishAttemptService)
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));

    IllegalStateException firstFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));

    assertTrue(
        firstFailure.getMessage().contains("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED"));
    assertEquals("failure receipt write failed", firstFailure.getCause().getMessage());
    assertEquals(PublishAttemptStatus.PENDING, pendingAttempt.getStatus());
    verify(publishAttemptService, org.mockito.Mockito.never())
        .markScriptPatchSucceeded(any(String.class));

    IllegalStateException retryFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "notes", PUBLISH_REQUEST_ID));
    assertTrue(
        retryFailure.getMessage().contains("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED"));
    verify(publishAttemptService, times(1))
        .createScriptPatchAttempt(
            any(VersionDto.class), any(String.class), any(Long.class), any(String.class));
    verify(publishAttemptService, times(1))
        .markScriptPatchFailed(any(String.class), any(String.class), any(String.class));
  }

  @Test
  void sameStableScriptPatchIdRejectsConflictingBaseBeforeAllocatingVersion() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding originalBinding =
        PublicationDigestRequestBinding.patch("tenant-1", "4", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt =
        scriptPatchAttempt(originalBinding, PublishAttemptStatus.PENDING, 11L, 8, 4L);
    when(publishAttemptService.findByPublishWorkflowId(
            "publish-script-patch:tenant-1:publish-request:" + PUBLISH_REQUEST_ID))
        .thenReturn(Optional.of(attempt));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "changed notes", PUBLISH_REQUEST_ID));

    assertTrue(thrown.getMessage().contains("PUBLISH_ATTEMPT_IDENTITY_CONFLICT"));
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  @Test
  void differentStableScriptPatchIdRejectsExistingEffectiveArtifactBeforeAllocatingVersion() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version retained =
        scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "original notes");
    when(versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
            "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(retained));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "different notes", "publish-request-2"));

    assertTrue(thrown.getMessage().contains("PUBLISH_SCRIPT_PATCH_IDENTITY_CONFLICT"));
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  @Test
  void sameStableScriptPatchIdReplaysFailedOutcomeWithoutAllocatingVersion() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.FAILED, 11L, 8, 3L);
    attempt.setFailureCode(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH.name());
    attempt.setFailureMessage("scope mismatch");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "different notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH, thrown.failureCode());
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"PUBLISH_FAILED", "LEGACY_REQUEST_IDENTITY_UNAVAILABLE"})
  void sameStableScriptPatchIdPreservesNonGateFailureCodeAndMessage(String failureCode) {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "3", "patch-2", PUBLISH_REQUEST_ID);
    PublishAttempt attempt = scriptPatchAttempt(binding, PublishAttemptStatus.FAILED, 11L, 8, 3L);
    attempt.setFailureCode(failureCode);
    attempt.setFailureMessage("stored terminal failure");
    when(publishAttemptService.findByPublishWorkflowId(binding.derivedWorkflowIdentity()))
        .thenReturn(Optional.of(attempt));

    ScriptPatchPublishFailureException thrown =
        assertThrows(
            ScriptPatchPublishFailureException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "different notes", PUBLISH_REQUEST_ID));

    assertEquals(failureCode, thrown.failureCode());
    assertEquals("stored terminal failure", thrown.getMessage());
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  @Test
  void retainedDraftScriptPatchTupleRejectsFreshPublicationWithoutAllocation() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version retainedDraft =
        scriptPatchVersion(12L, 9, 3L, VersionLifecycleState.DRAFT, "retained draft");
    when(versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
            "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(retainedDraft));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishScriptPatchVersion(
                    "tenant-1", 3L, "patch-2", "new notes", "publish-request-2"));

    assertTrue(thrown.getMessage().contains("PUBLISH_SCRIPT_PATCH_IDENTITY_CONFLICT"));
    verify(versionRepository, org.mockito.Mockito.never()).save(any(Version.class));
    verify(publishAttemptService, org.mockito.Mockito.never())
        .createScriptPatchAttempt(any(), any(), any(), any());
  }

  private PublishAttempt scriptPatchAttempt(
      PublicationDigestRequestBinding binding,
      PublishAttemptStatus status,
      long versionId,
      int versionNumber,
      long baseVersionId) {
    PublishAttempt attempt = new PublishAttempt();
    attempt.setId(101L);
    attempt.setTenantId(binding.tenantId());
    attempt.setPublishWorkflowId(binding.derivedWorkflowIdentity());
    attempt.setPublishType(PublishType.SCRIPT_PATCH);
    attempt.setStatus(status);
    attempt.setVersionId(versionId);
    attempt.setVersionNumber(versionNumber);
    attempt.setScriptPatchVersion(binding.scriptPatchVersion());
    attempt.setBaseVersionId(baseVersionId);
    attempt.setRequestDigest(binding.requestDigest());
    return attempt;
  }

  private Version scriptPatchVersion(
      long id, int versionNumber, long baseVersionId, VersionLifecycleState state, String notes) {
    Version version = new Version();
    version.setId(id);
    version.setTenantId("tenant-1");
    version.setVersionNumber(versionNumber);
    version.setVersionState(state);
    version.setVersionStateEpoch(state == VersionLifecycleState.PUBLISHED ? 2L : 1L);
    version.setScriptPatchVersion("patch-2");
    version.setBaseVersionId(baseVersionId);
    version.setScriptOnly(true);
    version.setNotes(notes);
    version.setCreatedAt(LocalDateTime.now());
    version.setUpdatedAt(LocalDateTime.now());
    return version;
  }

  @Test
  void publishVersionPropagatesTypedPublishGateFailures() {
    when(temporalPublishOrchestrator.publishFullVersion("tenant-1", "notes", PUBLISH_REQUEST_ID))
        .thenThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"));

    PublishGateFailureException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            PublishGateFailureException.class,
            () -> serviceWithTemporal().publishVersion("tenant-1", "notes", PUBLISH_REQUEST_ID));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
  }

  @Test
  void publishVersionDeletesExportedAssetsWhenAttestationWriteFails() {
    when(temporalPublishOrchestrator.publishFullVersion("tenant-1", "notes", PUBLISH_REQUEST_ID))
        .thenReturn(
            new VersionDto(
                10L,
                "tenant-1",
                8,
                VersionLifecycleState.PUBLISHED,
                2L,
                null,
                null,
                false,
                "notes",
                LocalDateTime.now(),
                LocalDateTime.now()));

    VersionDto dto = serviceWithTemporal().publishVersion("tenant-1", "notes", PUBLISH_REQUEST_ID);

    assertEquals(10L, dto.id());
    verify(temporalPublishOrchestrator).publishFullVersion("tenant-1", "notes", PUBLISH_REQUEST_ID);
  }

  private VersionServiceImpl serviceWithTemporal() {
    return new VersionServiceImpl(
        versionRepository,
        gameRepository,
        publishedPluginVersionRepository,
        pluginVersionStatusEventRepository,
        Mappers.getMapper(VersionMapper.class),
        publishAttemptService,
        publishGateService,
        controlPlaneDigestService,
        versionAssetArtifactService,
        publishedReleaseBundleService,
        recordedParticipantDigestService,
        pluginBundleIntakeService,
        pluginBundleStorageService,
        publishCommandService,
        Optional.of(temporalPublishOrchestrator));
  }

  private VersionServiceImpl serviceWithPublishGate(PublishGateService gate) {
    return new VersionServiceImpl(
        versionRepository,
        gameRepository,
        publishedPluginVersionRepository,
        pluginVersionStatusEventRepository,
        Mappers.getMapper(VersionMapper.class),
        publishAttemptService,
        gate,
        controlPlaneDigestService,
        versionAssetArtifactService,
        publishedReleaseBundleService,
        recordedParticipantDigestService,
        pluginBundleIntakeService,
        pluginBundleStorageService,
        publishCommandService,
        Optional.empty());
  }

  @Test
  void publishPluginVersionRequiresPublishedBaseVersionAbilityDigestMatch() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    PublishedPluginVersion uploaded = uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
    uploaded.setAbilitySchemaDigest("digest-requested");
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploaded));
    when(pluginBundleStorageService.loadPluginBundle("tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(new byte[] {1, 2, 3});
    when(pluginBundleIntakeService.parseAndVerify(any()))
        .thenReturn(parsedPluginBundle("plugin-1", "plugin-v1", 7L, "digest-requested"));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(publishedReleaseBundle("tenant-1", 7L, "digest-live"));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-requested",
                    "bundle-1",
                    1,
                    null,
                    null,
                    "signer-1",
                    false,
                    "ALLOWED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("VALIDATION_FAILED_DESIGN"));
  }

  @Test
  void publishPluginVersionRejectsRevokedSignerMetadata() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1")));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-live",
                    "bundle-1",
                    1,
                    null,
                    null,
                    "signer-1",
                    true,
                    "ALLOWED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("uploaded plugin bundle metadata"));
  }

  @Test
  void publishPluginVersionRejectsBlockedComponentPolicy() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1")));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-live",
                    "bundle-1",
                    1,
                    null,
                    null,
                    "signer-1",
                    false,
                    "BLOCKED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("blocked component policy"));
  }

  @Test
  void publishPluginVersionRejectsPublishRequestThatDoesNotMatchUploadedBundleMetadata() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1")));
    when(pluginBundleStorageService.loadPluginBundle("tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(new byte[] {1, 2, 3});
    when(pluginBundleIntakeService.parseAndVerify(any()))
        .thenReturn(parsedPluginBundle("plugin-1", "plugin-v1", 7L, "digest-live"));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(publishedReleaseBundle("tenant-1", 7L, "digest-live"));
    when(pluginBundleStorageService.exportPluginAssets(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            any(ParsedPluginBundle.class),
            org.mockito.ArgumentMatchers.eq("signer-1"),
            org.mockito.ArgumentMatchers.eq("bundle-1")))
        .thenReturn(new PluginDistributionManifest("", ""));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-live",
                    "bundle-1",
                    1,
                    "",
                    "",
                    "signer-2",
                    false,
                    "ALLOWED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("uploaded plugin bundle metadata"));
  }

  @Test
  void publishPluginVersionSupersedesOlderPublishedVersionsForSamePlugin() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));

    PublishedPluginVersion uploaded = uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v2");
    uploaded.setId(15L);
    PublishedPluginVersion olderPublished =
        uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
    olderPublished.setId(14L);
    olderPublished.setPublicationState(VersionLifecycleState.PUBLISHED);
    olderPublished.setComponentPolicyDecision("ALLOWED");

    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v2"))
        .thenReturn(Optional.of(uploaded));
    when(pluginBundleStorageService.loadPluginBundle("tenant-1", "plugin-1", "plugin-v2"))
        .thenReturn(new byte[] {1, 2, 3});
    when(pluginBundleIntakeService.parseAndVerify(any()))
        .thenReturn(parsedPluginBundle("plugin-1", "plugin-v2", 7L, "digest-live"));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(publishedReleaseBundle("tenant-1", 7L, "digest-live"));
    when(pluginBundleStorageService.exportPluginAssets(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            any(ParsedPluginBundle.class),
            org.mockito.ArgumentMatchers.eq("signer-1"),
            org.mockito.ArgumentMatchers.eq("bundle-1")))
        .thenReturn(new PluginDistributionManifest("", ""));
    when(publishedPluginVersionRepository.findAllByTenantIdAndPluginIdAndPublicationState(
            "tenant-1", "plugin-1", VersionLifecycleState.PUBLISHED))
        .thenReturn(List.of(olderPublished));
    when(publishedPluginVersionRepository.save(any(PublishedPluginVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PublishedPluginVersionDto published =
        service.publishPluginVersion(
            "tenant-1",
            "plugin-1",
            "plugin-v2",
            7L,
            "digest-live",
            "bundle-1",
            1,
            "",
            "",
            "signer-1",
            false,
            "ALLOWED",
            "notes");

    assertEquals(VersionLifecycleState.PUBLISHED, published.publicationState());
    assertEquals(VersionLifecycleState.SUPERSEDED, olderPublished.getPublicationState());
    assertEquals("superseded_by:plugin-v2", olderPublished.getStatusReason());
    verify(pluginVersionStatusEventRepository, times(2)).save(any());
  }

  @Test
  void publishPluginVersionRejectsTerminalRowsWithoutMutation() {
    for (VersionLifecycleState terminalState :
        List.of(VersionLifecycleState.SUPERSEDED, VersionLifecycleState.REVOKED_DESIGN)) {
      PublishedPluginVersion terminal = uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
      terminal.setPublicationState(terminalState);
      when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
              "tenant-1", "plugin-1", "plugin-v1"))
          .thenReturn(Optional.of(terminal));

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  service.publishPluginVersion(
                      "tenant-1",
                      "plugin-1",
                      "plugin-v1",
                      7L,
                      "digest-live",
                      "bundle-1",
                      1,
                      "",
                      "",
                      "signer-1",
                      false,
                      "ALLOWED",
                      "notes"));

      assertTrue(thrown.getMessage().startsWith("PLUGIN_VERSION_IMMUTABLE:"));
      assertTrue(thrown.getMessage().contains("terminal plugin version"));
      assertEquals(terminalState, terminal.getPublicationState());
    }

    verify(publishedPluginVersionRepository, org.mockito.Mockito.never())
        .save(any(PublishedPluginVersion.class));
    verify(pluginBundleStorageService, org.mockito.Mockito.never())
        .loadPluginBundle(any(), any(), any());
    verify(pluginVersionStatusEventRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void revokePluginVersionTransitionsToRevokedDesignAndAppendsEvent() {
    PublishedPluginVersion published = uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
    published.setPublicationState(VersionLifecycleState.PUBLISHED);
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(published));
    when(publishedPluginVersionRepository.save(any(PublishedPluginVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PublishedPluginVersionDto revoked =
        service.revokePluginVersion("tenant-1", "plugin-1", "plugin-v1", "signer_revoked");

    assertEquals(VersionLifecycleState.REVOKED_DESIGN, revoked.publicationState());
    assertEquals("signer_revoked", revoked.statusReason());
    verify(pluginVersionStatusEventRepository).save(any());
  }

  @Test
  void getDesignControlPlaneDigestUsesStoredVersionScope() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(2L);
    version.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "7", "version:7", "digest-1", 1));

    DesignControlPlaneDigestDto dto = service.getDesignControlPlaneDigest("tenant-1", 7L);

    assertEquals("digest-1", dto.contentDigest());
  }

  @Test
  void getPublishedScriptPatchVersionRejectsMissingBaseScope() {
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getPublishedScriptPatchVersion("tenant-1", 3L, "patch-2"));
  }

  @ParameterizedTest
  @EnumSource(
      value = VersionLifecycleState.class,
      names = {"PUBLISHED", "ACTIVE", "RETIRED"})
  void getPublishedScriptPatchVersionReadsRetainedPublicationStates(VersionLifecycleState state) {
    Version retained = scriptPatchVersion(11L, 8, 3L, state, "notes");
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(retained));

    VersionDto result = service.getPublishedScriptPatchVersion("tenant-1", 3L, "patch-2");

    assertEquals(state, result.versionState());
    assertEquals(11L, result.id());
  }

  @Test
  void getPublishedScriptPatchVersionRejectsAmbiguousBaseScope() {
    Version first = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "notes");
    Version second = scriptPatchVersion(12L, 9, 3L, VersionLifecycleState.PUBLISHED, "notes");
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getPublishedScriptPatchVersion("tenant-1", 3L, "patch-2"));
  }

  @Test
  void getPublishedScriptPatchVersionRejectsNonPositiveBase() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.getPublishedScriptPatchVersion("tenant-1", 0L, "patch-2"));
    verify(versionRepository, times(0))
        .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
            any(), any(), any());
  }

  @Test
  void getPublishedPluginVersionRejectsUnpublishedVersion() {
    PublishedPluginVersion uploaded = uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploaded));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getPublishedPluginVersion("tenant-1", "plugin-1", "plugin-v1"));
  }

  @Test
  void getPublishedPluginVersionReadsHistoricalTerminalVersions() {
    for (VersionLifecycleState state :
        List.of(VersionLifecycleState.SUPERSEDED, VersionLifecycleState.REVOKED_DESIGN)) {
      PublishedPluginVersion historical =
          uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
      historical.setPublicationState(state);
      when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
              "tenant-1", "plugin-1", "plugin-v1"))
          .thenReturn(Optional.of(historical));

      assertEquals(
          state,
          service
              .getPublishedPluginVersion("tenant-1", "plugin-1", "plugin-v1")
              .publicationState());
    }
  }

  @Test
  void getDesignControlPlaneDigestForScriptPatchRejectsMissingPublishedScope() {
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getDesignControlPlaneDigestForScriptPatch("tenant-1", 3L, "patch-2"));
    verify(controlPlaneDigestService, org.mockito.Mockito.never())
        .getDigestForScriptPatch(any(VersionDto.class));
  }

  @ParameterizedTest
  @EnumSource(
      value = VersionLifecycleState.class,
      names = {"PUBLISHED", "ACTIVE", "RETIRED"})
  void getDesignControlPlaneDigestForScriptPatchReadsRetainedPublicationStates(
      VersionLifecycleState state) {
    Version retained = scriptPatchVersion(11L, 8, 3L, state, "notes");
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(retained));
    when(controlPlaneDigestService.getDigestForScriptPatch(any(VersionDto.class)))
        .thenReturn(
            new DesignControlPlaneDigestDto(
                "tenant-1", "patch-2", "script-patch:patch-2", "digest-1", 1));

    DesignControlPlaneDigestDto result =
        service.getDesignControlPlaneDigestForScriptPatch("tenant-1", 3L, "patch-2");

    assertEquals("digest-1", result.contentDigest());
    ArgumentCaptor<VersionDto> versionCaptor = ArgumentCaptor.forClass(VersionDto.class);
    verify(controlPlaneDigestService).getDigestForScriptPatch(versionCaptor.capture());
    assertEquals(state, versionCaptor.getValue().versionState());
  }

  @Test
  void getDesignControlPlaneDigestForScriptPatchRejectsWrongBaseScope() {
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 99L, "patch-2"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getDesignControlPlaneDigestForScriptPatch("tenant-1", 99L, "patch-2"));
    verify(controlPlaneDigestService, org.mockito.Mockito.never())
        .getDigestForScriptPatch(any(VersionDto.class));
  }

  @Test
  void getDesignControlPlaneDigestForScriptPatchRejectsAmbiguousSamePatchScope() {
    Version first = scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "first");
    Version second = scriptPatchVersion(12L, 9, 3L, VersionLifecycleState.PUBLISHED, "second");
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getDesignControlPlaneDigestForScriptPatch("tenant-1", 3L, "patch-2"));
    verify(controlPlaneDigestService, org.mockito.Mockito.never())
        .getDigestForScriptPatch(any(VersionDto.class));
  }

  @Test
  void getDesignControlPlaneDigestForScriptPatchKeepsSamePatchBasesSeparate() {
    Version baseThree =
        scriptPatchVersion(11L, 8, 3L, VersionLifecycleState.PUBLISHED, "base-three");
    Version baseFour = scriptPatchVersion(12L, 9, 4L, VersionLifecycleState.PUBLISHED, "base-four");
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 3L, "patch-2"))
        .thenReturn(List.of(baseThree));
    when(versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                "tenant-1", 4L, "patch-2"))
        .thenReturn(List.of(baseFour));
    when(controlPlaneDigestService.getDigestForScriptPatch(any(VersionDto.class)))
        .thenReturn(
            new DesignControlPlaneDigestDto("tenant-1", "patch-2", "commit-1", "digest", 1));

    service.getDesignControlPlaneDigestForScriptPatch("tenant-1", 3L, "patch-2");
    service.getDesignControlPlaneDigestForScriptPatch("tenant-1", 4L, "patch-2");

    ArgumentCaptor<VersionDto> versionCaptor = ArgumentCaptor.forClass(VersionDto.class);
    verify(controlPlaneDigestService, times(2)).getDigestForScriptPatch(versionCaptor.capture());
    assertEquals(11L, versionCaptor.getAllValues().get(0).id());
    assertEquals(3L, versionCaptor.getAllValues().get(0).baseVersionId());
    assertEquals(12L, versionCaptor.getAllValues().get(1).id());
    assertEquals(4L, versionCaptor.getAllValues().get(1).baseVersionId());
  }

  @Test
  void listPublishedPluginVersionsUsesRepositoryFiltersAndLimitClamp() {
    PublishedPluginVersion newer = new PublishedPluginVersion();
    newer.setId(22L);
    newer.setTenantId("tenant-1");
    newer.setPluginId("plugin-1");
    newer.setPluginVersionId("plugin-v2");
    newer.setBaseVersionId(7L);
    newer.setPublicationState(VersionLifecycleState.PUBLISHED);
    newer.setAbilitySchemaDigest("ability-2");
    newer.setBundleDigest("bundle-2");
    newer.setManifestSchemaVersion(1);
    newer.setSignerKeyId("signer-2");
    newer.setSignerRevoked(false);
    newer.setComponentPolicyDecision("REPORT_ONLY");
    newer.setLastChangedAt(LocalDateTime.parse("2026-04-22T10:00:00"));

    when(publishedPluginVersionRepository.listPublishedPluginVersions(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("plugin-1"),
            org.mockito.ArgumentMatchers.eq(VersionLifecycleState.PUBLISHED),
            org.mockito.ArgumentMatchers.eq(LocalDateTime.parse("2026-04-20T10:00:00")),
            isNull(),
            any(Pageable.class)))
        .thenReturn(List.of(newer));

    List<PublishedPluginVersionDto> results =
        service.listPublishedPluginVersions(
            "tenant-1",
            "plugin-1",
            VersionLifecycleState.PUBLISHED,
            LocalDateTime.parse("2026-04-20T10:00:00"),
            null,
            500);

    assertEquals(1, results.size());
    assertEquals("plugin-v2", results.get(0).pluginVersionId());
    org.mockito.ArgumentCaptor<Pageable> pageableCaptor =
        org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(publishedPluginVersionRepository)
        .listPublishedPluginVersions(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("plugin-1"),
            org.mockito.ArgumentMatchers.eq(VersionLifecycleState.PUBLISHED),
            org.mockito.ArgumentMatchers.eq(LocalDateTime.parse("2026-04-20T10:00:00")),
            isNull(),
            pageableCaptor.capture());
    assertEquals(200, pageableCaptor.getValue().getPageSize());
  }

  @Test
  void listPublishedPluginVersionsRejectsInvertedTimeWindow() {
    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                service.listPublishedPluginVersions(
                    "tenant-1",
                    "",
                    null,
                    LocalDateTime.parse("2026-04-22T10:00:00"),
                    LocalDateTime.parse("2026-04-20T10:00:00"),
                    10));

    assertTrue(thrown.getMessage().contains("changedAfter"));
  }

  @Test
  void listPluginVersionStatusEventsUsesRepositoryFiltersAndLimitClamp() {
    net.firedevops.firemud.gamedesign.entity.PluginVersionStatusEvent event =
        new net.firedevops.firemud.gamedesign.entity.PluginVersionStatusEvent();
    event.setEventId("ppse-1");
    event.setTenantId("tenant-1");
    event.setPluginId("plugin-1");
    event.setPluginVersionId("plugin-v1");
    event.setPreviousPublicationState(VersionLifecycleState.SIGNATURE_VERIFIED);
    event.setNewPublicationState(VersionLifecycleState.PUBLISHED);
    event.setStatusReason("published");
    event.setObservedAt(java.time.Instant.parse("2026-04-26T10:00:00Z"));
    when(pluginVersionStatusEventRepository.findEvents(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("plugin-1"),
            org.mockito.ArgumentMatchers.eq("plugin-v1"),
            org.mockito.ArgumentMatchers.eq(VersionLifecycleState.PUBLISHED),
            org.mockito.ArgumentMatchers.eq(java.time.Instant.parse("2026-04-25T10:00:00Z")),
            isNull(),
            any(Pageable.class)))
        .thenReturn(List.of(event));

    List<PluginVersionStatusEventDto> results =
        service.listPluginVersionStatusEvents(
            "tenant-1",
            "plugin-1",
            "plugin-v1",
            VersionLifecycleState.PUBLISHED,
            LocalDateTime.parse("2026-04-25T10:00:00"),
            null,
            500);

    assertEquals(1, results.size());
    assertEquals("ppse-1", results.get(0).eventId());
    org.mockito.ArgumentCaptor<Pageable> pageableCaptor =
        org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(pluginVersionStatusEventRepository)
        .findEvents(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("plugin-1"),
            org.mockito.ArgumentMatchers.eq("plugin-v1"),
            org.mockito.ArgumentMatchers.eq(VersionLifecycleState.PUBLISHED),
            org.mockito.ArgumentMatchers.eq(java.time.Instant.parse("2026-04-25T10:00:00Z")),
            isNull(),
            pageableCaptor.capture());
    assertEquals(200, pageableCaptor.getValue().getPageSize());
  }

  @Test
  void listVersionsUsesTenantScopedOrdering() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantId("tenant-1")).thenReturn(game);

    Version one = new Version();
    one.setId(1L);
    one.setTenantId("tenant-1");
    one.setVersionNumber(1);
    one.setVersionState(VersionLifecycleState.PUBLISHED);
    one.setVersionStateEpoch(2L);
    one.setUpdatedAt(java.time.LocalDateTime.now());
    Version two = new Version();
    two.setId(2L);
    two.setTenantId("tenant-1");
    two.setVersionNumber(2);
    two.setVersionState(VersionLifecycleState.PUBLISHED);
    two.setVersionStateEpoch(2L);
    two.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.findAllByTenantIdOrderByVersionNumberAsc("tenant-1"))
        .thenReturn(List.of(one, two));

    List<VersionDto> versions = service.listVersions("tenant-1");

    assertEquals(2, versions.size());
    assertTrue(versions.get(0).versionNumber() < versions.get(1).versionNumber());
    verify(versionRepository).findAllByTenantIdOrderByVersionNumberAsc("tenant-1");
  }

  private PublishedReleaseBundleDto publishedReleaseBundle(
      String tenantId, long versionId, String automationDigest) {
    return new PublishedReleaseBundleDto(
        1L,
        tenantId,
        versionId,
        7,
        "v1",
        "workflow-1",
        "manifest-1",
        List.of("manifest.json"),
        List.of(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                String.valueOf(versionId),
                "version:" + versionId,
                automationDigest,
                1,
                null,
                null)),
        "genrev-1",
        false,
        null,
        LocalDateTime.parse("2026-04-26T10:00:00"));
  }

  private PublishedPluginVersion uploadedPluginVersion(
      String tenantId, String pluginId, String pluginVersionId) {
    PublishedPluginVersion entity = new PublishedPluginVersion();
    entity.setId(15L);
    entity.setTenantId(tenantId);
    entity.setPluginId(pluginId);
    entity.setPluginVersionId(pluginVersionId);
    entity.setBaseVersionId(7L);
    entity.setPublicationState(VersionLifecycleState.SIGNATURE_VERIFIED);
    entity.setAbilitySchemaDigest("digest-live");
    entity.setBundleDigest("bundle-1");
    entity.setManifestSchemaVersion(1);
    entity.setDistributionManifestHash("");
    entity.setDistributionManifestPath("");
    entity.setSignerKeyId("signer-1");
    entity.setSignerRevoked(false);
    entity.setComponentPolicyDecision("UNSPECIFIED");
    entity.setNotes("notes");
    entity.setStatusReason("");
    entity.setLastChangedAt(LocalDateTime.parse("2026-04-26T10:00:00"));
    return entity;
  }

  private ParsedPluginBundle parsedPluginBundle(
      String pluginId, String pluginVersionId, long baseVersionId, String abilitySchemaDigest) {
    return new ParsedPluginBundle(
        pluginId,
        pluginVersionId,
        baseVersionId,
        abilitySchemaDigest,
        "bundle-1",
        1,
        "signer-1",
        List.of(),
        java.util.Map.of("plugin-manifest.json", new byte[] {1}));
  }
}
