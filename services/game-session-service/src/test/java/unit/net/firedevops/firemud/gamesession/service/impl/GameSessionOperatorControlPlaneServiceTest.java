package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.ExpectedCurrentPin;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import org.junit.jupiter.api.Test;

class GameSessionOperatorControlPlaneServiceTest {
  @Test
  void rejectsScriptPinEpochExhaustionAsDurableStateFailureWithoutMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setScriptPatchVersion("patch-old");
    instance.setScriptPinEpoch(Long.MAX_VALUE);
    instance.setScriptPatchPinnedControlPlaneRequestId("request-0");
    when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                service.setPinnedScriptPatchVersion(
                    1L,
                    7L,
                    SetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("patch-new")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("pin")
                        .setExpectedCurrentPin(
                            ExpectedCurrentPin.newBuilder()
                                .setKind(ExpectedCurrentPin.Kind.EXPECT_EPOCH)
                                .setScriptPinEpoch(Long.MAX_VALUE)
                                .build())
                        .build()))
        .withMessage("script pin epoch exhausted");

    org.assertj.core.api.Assertions.assertThat(instance.getScriptPatchVersion())
        .isEqualTo("patch-old");
    org.assertj.core.api.Assertions.assertThat(instance.getScriptPinEpoch())
        .isEqualTo(Long.MAX_VALUE);
    org.mockito.Mockito.verify(gameInstanceRepository, org.mockito.Mockito.never())
        .save(org.mockito.Mockito.any(GameInstance.class));
    verifyNoInteractions(tickService);
  }

  @Test
  void rejectsMalformedCurrentScriptPinBeforeMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setScriptPatchVersion("patch-old");
    when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.setPinnedScriptPatchVersion(
                    1L,
                    7L,
                    SetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("patch-new")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("pin")
                        .setExpectedCurrentPin(
                            ExpectedCurrentPin.newBuilder()
                                .setKind(ExpectedCurrentPin.Kind.EXPECT_UNPINNED)
                                .build())
                        .build()))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");

    org.assertj.core.api.Assertions.assertThat(instance.getScriptPatchVersion())
        .isEqualTo("patch-old");
    org.assertj.core.api.Assertions.assertThat(instance.getScriptPinEpoch()).isNull();
    org.mockito.Mockito.verify(gameInstanceRepository, org.mockito.Mockito.never())
        .save(org.mockito.Mockito.any(GameInstance.class));
    verifyNoInteractions(tickService);
  }

  @Test
  void rejectsBlankScriptPatchPinTargetBeforeRepositoryReadOrMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    GameSessionProperties gameSessionProperties = mock(GameSessionProperties.class);
    GameSessionOperatorControlPlaneService service =
        new GameSessionOperatorControlPlaneService(
            gameInstanceRepository, tickService, gameDesignClient, gameSessionProperties);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.setPinnedScriptPatchVersion(
                    1L,
                    7L,
                    SetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("   ")
                        .setControlPlaneRequestId("request-1")
                        .build()))
        .withMessage("target_script_patch_version is required");

    verifyNoInteractions(
        gameInstanceRepository, tickService, gameDesignClient, gameSessionProperties);
  }

  @Test
  void rejectsBlankScriptPatchRollbackTargetBeforeRepositoryReadOrMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    GameSessionProperties gameSessionProperties = mock(GameSessionProperties.class);
    GameSessionOperatorControlPlaneService service =
        new GameSessionOperatorControlPlaneService(
            gameInstanceRepository, tickService, gameDesignClient, gameSessionProperties);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.rollbackScriptPatchVersion(
                    1L,
                    7L,
                    RollbackScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("")
                        .setControlPlaneRequestId("request-1")
                        .build()))
        .withMessage("target_script_patch_version is required");

    verifyNoInteractions(
        gameInstanceRepository, tickService, gameDesignClient, gameSessionProperties);
  }

  @Test
  void rejectsBlankScriptPinAuditMetadataBeforeRepositoryReadOrMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.setPinnedScriptPatchVersion(
                    1L,
                    7L,
                    SetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("patch-new")
                        .setActorPrincipal("operator")
                        .setReason("pin")
                        .build()))
        .withMessage("control_plane_request_id is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankScriptRollbackAuditMetadataBeforeRepositoryReadOrMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.rollbackScriptPatchVersion(
                    1L,
                    7L,
                    RollbackScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("patch-old")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankScriptPinActorBeforeRepositoryReadOrMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.setPinnedScriptPatchVersion(
                    1L,
                    7L,
                    SetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTargetScriptPatchVersion("patch-new")
                        .setControlPlaneRequestId("request-1")
                        .setReason("pin")
                        .build()))
        .withMessage("actor_principal is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankScriptPatchRollbackPurgeReasonBeforePersistence() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.purgeQueuedTickCommandsForScriptPatch(
                    1L,
                    7L,
                    PurgeQueuedTickCommandsForScriptPatchRequest.newBuilder()
                        .setScriptPatchVersion("patch-1")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("   ")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankPluginRollbackPurgeReasonBeforePersistence() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.purgeQueuedTickCommandsForPluginVersion(
                    1L,
                    7L,
                    PurgeQueuedTickCommandsForPluginVersionRequest.newBuilder()
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankPauseTicksReasonBeforePersistence() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.pauseTicksForScope(
                    1L,
                    PauseTicksForScopeRequest.newBuilder()
                        .setGameInstanceId("7")
                        .setReason("   ")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankResumeTicksReasonBeforePersistence() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.resumeTicksForScope(
                    1L,
                    ResumeTicksForScopeRequest.newBuilder()
                        .setGameInstanceId("7")
                        .setReason("")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  private GameSessionOperatorControlPlaneService service(
      GameInstanceRepository gameInstanceRepository, TickService tickService) {
    return new GameSessionOperatorControlPlaneService(
        gameInstanceRepository,
        tickService,
        mock(GameDesignClient.class),
        mock(GameSessionProperties.class));
  }
}
