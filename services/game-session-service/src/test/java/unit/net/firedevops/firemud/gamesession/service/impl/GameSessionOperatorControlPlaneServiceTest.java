package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.AutomationScriptingControlPlaneClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.ScriptPinMutationResult;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.ExpectedCurrentPin;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GameSessionOperatorControlPlaneServiceTest {
  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void readyPublishedBaseCompatiblePatchIsPinnedAfterBothOwnerReads() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = validUnpinnedInstance();
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    when(repository.applyScriptPin(
            1L, 7L, "SET", "patch-new", "request-1", "operator", "pin", "EXPECT_UNPINNED", null))
        .thenReturn(new ScriptPinMutationResult(null, null, "patch-new", 1L, "request-1", null));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    org.assertj.core.api.Assertions.assertThat(response.hasError()).isFalse();
    org.mockito.Mockito.verify(repository)
        .applyScriptPin(
            1L, 7L, "SET", "patch-new", "request-1", "operator", "pin", "EXPECT_UNPINNED", null);
  }

  @Test
  void rejectsEpochValueOnExpectUnpinnedBeforeOwnerReads() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    SetPinnedScriptPatchVersionRequest request =
        setRequest("request-1").toBuilder()
            .setExpectedCurrentPin(
                ExpectedCurrentPin.newBuilder()
                    .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_UNPINNED)
                    .setScriptPinEpoch(7L)
                    .build())
            .build();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                newService(repository, tickService, gameDesign, automation)
                    .setPinnedScriptPatchVersion(1L, 7L, request))
        .withMessage("expected_current_pin script_pin_epoch must be absent for EXPECT_UNPINNED");
    verifyNoInteractions(repository, gameDesign, automation);
  }

  @Test
  void rejectsEpochValueOnUnconditionalBeforeOwnerReads() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    SetPinnedScriptPatchVersionRequest request =
        setRequest("request-1").toBuilder()
            .setExpectedCurrentPin(
                ExpectedCurrentPin.newBuilder()
                    .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_UNCONDITIONAL)
                    .setScriptPinEpoch(7L)
                    .build())
            .build();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                newService(repository, tickService, gameDesign, automation)
                    .setPinnedScriptPatchVersion(1L, 7L, request))
        .withMessage("expected_current_pin script_pin_epoch must be absent for UNCONDITIONAL");
    verifyNoInteractions(repository, gameDesign, automation);
  }

  @Test
  void unconditionalRequiresPlatformAdminBeforeOwnerReads() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    SessionContext.setContext(
        "42", java.util.List.of(), java.util.Map.of("1", java.util.List.of("tenantAdmin")));
    SetPinnedScriptPatchVersionRequest request =
        setRequest("request-1").toBuilder()
            .setExpectedCurrentPin(
                ExpectedCurrentPin.newBuilder()
                    .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_UNCONDITIONAL)
                    .build())
            .build();

    assertThatExceptionOfType(AdminAuthorizationException.class)
        .isThrownBy(
            () ->
                newService(repository, tickService, gameDesign, automation)
                    .setPinnedScriptPatchVersion(1L, 7L, request))
        .withMessage("UNCONDITIONAL requires platformAdmin");
    verifyNoInteractions(repository, gameDesign, automation);
  }

  @Test
  void rollbackToCurrentPatchIsLedgeredAsDeterministicFailure() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setVersionId(100L);
    instance.setScriptPatchVersion("patch-current");
    instance.setScriptPinEpoch(3L);
    instance.setScriptPatchPinnedControlPlaneRequestId("request-0");
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "ROLLBACK",
            "patch-current",
            "request-1",
            "operator",
            "rollback",
            "EXPECT_EPOCH",
            3L,
            "SCRIPT_PATCH_ROLLBACK_TARGET_CURRENT"))
        .thenReturn(
            new ScriptPinMutationResult(
                "patch-current",
                3L,
                "patch-current",
                3L,
                "request-1",
                "SCRIPT_PATCH_ROLLBACK_TARGET_CURRENT"));
    RollbackScriptPatchVersionRequest request =
        RollbackScriptPatchVersionRequest.newBuilder()
            .setTargetScriptPatchVersion("patch-current")
            .setControlPlaneRequestId("request-1")
            .setActorPrincipal("operator")
            .setReason("rollback")
            .setExpectedCurrentPin(
                ExpectedCurrentPin.newBuilder()
                    .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH)
                    .setScriptPinEpoch(3L)
                    .build())
            .build();

    var response =
        newService(repository, tickService, gameDesign, automation)
            .rollbackScriptPatchVersion(1L, 7L, request);

    org.assertj.core.api.Assertions.assertThat(response.getError().getCode())
        .isEqualTo("SCRIPT_PATCH_ROLLBACK_TARGET_CURRENT");
    org.assertj.core.api.Assertions.assertThat(response.getError().getMessage())
        .isEqualTo("rollback target is already the current script patch");
    org.mockito.Mockito.verify(repository)
        .recordScriptPinFailure(
            1L,
            7L,
            "ROLLBACK",
            "patch-current",
            "request-1",
            "operator",
            "rollback",
            "EXPECT_EPOCH",
            3L,
            "SCRIPT_PATCH_ROLLBACK_TARGET_CURRENT");
    verifyNoInteractions(gameDesign, automation);
  }

  @Test
  void pinFailureMessagesCoverAllTargetValidationCodes() throws ReflectiveOperationException {
    GameSessionOperatorControlPlaneService service =
        service(mock(GameInstanceRepository.class), mock(TickService.class));
    var messageMethod =
        GameSessionOperatorControlPlaneService.class.getDeclaredMethod(
            "pinFailureMessage", String.class);
    messageMethod.setAccessible(true);

    var expectedMessages =
        java.util.Map.of(
            "SCRIPT_PIN_EXPECTATION_FAILED",
            "expected current script pin does not match authoritative current tuple",
            "SCRIPT_PIN_EPOCH_EXHAUSTED",
            "script pin epoch exhausted",
            "SCRIPT_PATCH_ROLLBACK_TARGET_CURRENT",
            "rollback target is already the current script patch",
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE",
            "script patch authority is unavailable",
            "SCRIPT_PATCH_NOT_PUBLISHED",
            "script patch is not published",
            "SCRIPT_PATCH_NOT_READY",
            "script patch is not ready",
            "SCRIPT_PATCH_TENANT_MISMATCH",
            "script patch tenant does not match the game instance tenant",
            "SCRIPT_PATCH_BASE_VERSION_MISMATCH",
            "script patch base version does not match the game instance runtime version");

    expectedMessages.forEach(
        (errorCode, expectedMessage) ->
            assertThat(invokePinFailureMessage(messageMethod, service, errorCode))
                .isEqualTo(expectedMessage));
  }

  @Test
  void unreadyPatchIsLedgeredAndDoesNotMutatePin() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = validUnpinnedInstance();
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_PENDING_VALIDATION)
                .setBaseVersionId(100L)
                .build());
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_NOT_READY"))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, "request-1", "SCRIPT_PATCH_NOT_READY"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    org.assertj.core.api.Assertions.assertThat(response.getError().getCode())
        .isEqualTo("SCRIPT_PATCH_NOT_READY");
    org.assertj.core.api.Assertions.assertThat(instance.getScriptPatchVersion()).isNull();
    verifyNoScriptPinMutation(repository);
  }

  @Test
  void tenantMismatchIsLedgeredBeforePinMutation() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    when(repository.findById(7L)).thenReturn(Optional.of(validUnpinnedInstance()));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(2L, 200L, 100L));
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_TENANT_MISMATCH"))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, "request-1", "SCRIPT_PATCH_TENANT_MISMATCH"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    org.assertj.core.api.Assertions.assertThat(response.getError().getCode())
        .isEqualTo("SCRIPT_PATCH_TENANT_MISMATCH");
    verifyNoInteractions(automation);
    verifyNoScriptPinMutation(repository);
  }

  @Test
  void baseMismatchIsLedgeredBeforePinMutation() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    when(repository.findById(7L)).thenReturn(Optional.of(validUnpinnedInstance()));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 999L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(999L)
                .build());
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_BASE_VERSION_MISMATCH"))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, "request-1", "SCRIPT_PATCH_BASE_VERSION_MISMATCH"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    org.assertj.core.api.Assertions.assertThat(response.getError().getCode())
        .isEqualTo("SCRIPT_PATCH_BASE_VERSION_MISMATCH");
    verifyNoScriptPinMutation(repository);
  }

  @Test
  void missingRuntimeVersionIsClassifiedAsUnavailableAuthority() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = validUnpinnedInstance();
    instance.setVersionId(null);
    instance.setRuntimeVersion(null);
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, "request-1", "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    assertThat(response.getError().getCode()).isEqualTo("SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
    verifyNoScriptPinMutation(repository);
  }

  @Test
  void nonnumericRuntimeVersionIsClassifiedAsUnavailableAuthority() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = validUnpinnedInstance();
    instance.setVersionId(null);
    instance.setRuntimeVersion("runtime-version");
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, "request-1", "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    assertThat(response.getError().getCode()).isEqualTo("SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
    verifyNoScriptPinMutation(repository);
  }

  @Test
  void unavailableAuthorityRecordsEachAttemptAndReplaysStoredFailure() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    when(repository.findById(7L)).thenReturn(Optional.of(validUnpinnedInstance()));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("UNKNOWN_PUBLICATION_FAILURE")
                        .setMessage("down")
                        .build())
                .build());
    ScriptPinMutationResult failure =
        new ScriptPinMutationResult(
            null, null, null, null, "request-1", "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"))
        .thenReturn(failure);

    GameSessionOperatorControlPlaneService service =
        newService(repository, tickService, gameDesign, automation);
    SetPinnedScriptPatchVersionResponse first =
        service.setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));
    SetPinnedScriptPatchVersionResponse retry =
        service.setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    org.assertj.core.api.Assertions.assertThat(first.getError().getCode())
        .isEqualTo("SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
    org.assertj.core.api.Assertions.assertThat(retry).isEqualTo(first);
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
        .recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
  }

  @Test
  void returnsLedgeredScriptPinEpochExhaustionFailureWithoutLocalMutation() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setVersionId(100L);
    instance.setScriptPatchVersion("patch-old");
    instance.setScriptPinEpoch(Long.MAX_VALUE);
    instance.setScriptPatchPinnedControlPlaneRequestId("request-0");
    when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameInstanceRepository.applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_EPOCH",
            Long.MAX_VALUE))
        .thenReturn(
            new ScriptPinMutationResult(
                "patch-old",
                Long.MAX_VALUE,
                "patch-old",
                Long.MAX_VALUE,
                "request-1",
                "SCRIPT_PIN_EPOCH_EXHAUSTED"));
    when(gameDesignClient.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    GameSessionOperatorControlPlaneService service =
        newService(gameInstanceRepository, tickService, gameDesignClient, automation);

    SetPinnedScriptPatchVersionResponse response =
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
                        .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH)
                        .setScriptPinEpoch(Long.MAX_VALUE)
                        .build())
                .build());

    org.assertj.core.api.Assertions.assertThat(response.getError().getCode())
        .isEqualTo("SCRIPT_PIN_EPOCH_EXHAUSTED");
    org.assertj.core.api.Assertions.assertThat(response.getError().getMessage())
        .isEqualTo("script pin epoch exhausted");

    org.assertj.core.api.Assertions.assertThat(instance.getScriptPatchVersion())
        .isEqualTo("patch-old");
    org.assertj.core.api.Assertions.assertThat(instance.getScriptPinEpoch())
        .isEqualTo(Long.MAX_VALUE);
    org.mockito.Mockito.verify(gameInstanceRepository, org.mockito.Mockito.never())
        .save(org.mockito.Mockito.any(GameInstance.class));
    org.mockito.Mockito.verify(gameInstanceRepository)
        .applyScriptPin(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_EPOCH",
            Long.MAX_VALUE);
    verifyNoInteractions(tickService);
  }

  @Test
  void setPinExpectationFailureWithMissingRequestIdUsesEmptyStructuredResponseId() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    when(repository.findById(7L)).thenReturn(Optional.of(validUnpinnedInstance()));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    when(repository.applyScriptPin(
            1L, 7L, "SET", "patch-new", "request-1", "operator", "pin", "EXPECT_UNPINNED", null))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, null, "SCRIPT_PIN_EXPECTATION_FAILED"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    assertThat(response.getControlPlaneRequestId()).isEmpty();
    assertThat(response.getError().getCode()).isEqualTo("SCRIPT_PIN_EXPECTATION_FAILED");
    verify(repository)
        .applyScriptPin(
            1L, 7L, "SET", "patch-new", "request-1", "operator", "pin", "EXPECT_UNPINNED", null);
  }

  @Test
  void rollbackExpectationFailureWithMissingRequestIdUsesEmptyStructuredResponseId() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    GameInstance instance = validUnpinnedInstance();
    instance.setScriptPatchVersion("patch-old");
    instance.setScriptPinEpoch(3L);
    instance.setScriptPatchPinnedControlPlaneRequestId("request-0");
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(automation.getScriptPatchStatus(1L, "patch-new"))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    when(repository.applyScriptPin(
            1L,
            7L,
            "ROLLBACK",
            "patch-new",
            "request-1",
            "operator",
            "rollback",
            "EXPECT_EPOCH",
            3L))
        .thenReturn(
            new ScriptPinMutationResult(
                "patch-old", 3L, "patch-old", 3L, null, "SCRIPT_PIN_EXPECTATION_FAILED"));
    RollbackScriptPatchVersionRequest request =
        RollbackScriptPatchVersionRequest.newBuilder()
            .setTargetScriptPatchVersion("patch-new")
            .setControlPlaneRequestId("request-1")
            .setActorPrincipal("operator")
            .setReason("rollback")
            .setExpectedCurrentPin(
                ExpectedCurrentPin.newBuilder()
                    .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH)
                    .setScriptPinEpoch(3L)
                    .build())
            .build();

    var response =
        newService(repository, tickService, gameDesign, automation)
            .rollbackScriptPatchVersion(1L, 7L, request);

    assertThat(response.getControlPlaneRequestId()).isEmpty();
    assertThat(response.getError().getCode()).isEqualTo("SCRIPT_PIN_EXPECTATION_FAILED");
    verify(repository)
        .applyScriptPin(
            1L,
            7L,
            "ROLLBACK",
            "patch-new",
            "request-1",
            "operator",
            "rollback",
            "EXPECT_EPOCH",
            3L);
  }

  @Test
  void missingAutomationAuthorityFailsClosedBeforePinMutation() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    GameInstance instance = validUnpinnedInstance();
    when(repository.findById(7L)).thenReturn(Optional.of(instance));
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-new"))
        .thenReturn(publishedPatch(1L, 200L, 100L));
    when(repository.recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"))
        .thenReturn(
            new ScriptPinMutationResult(
                null, null, null, null, "request-1", "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, null)
            .setPinnedScriptPatchVersion(1L, 7L, setRequest("request-1"));

    org.assertj.core.api.Assertions.assertThat(response.getError().getCode())
        .isEqualTo("SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
    org.mockito.Mockito.verify(repository)
        .recordScriptPinFailure(
            1L,
            7L,
            "SET",
            "patch-new",
            "request-1",
            "operator",
            "pin",
            "EXPECT_UNPINNED",
            null,
            "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE");
    verifyNoScriptPinMutation(repository);
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
                                .setKind(
                                    ExpectedCurrentPin.Kind
                                        .EXPECTED_CURRENT_PIN_KIND_EXPECT_UNPINNED)
                                .build())
                        .build()))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                + " together");

    org.assertj.core.api.Assertions.assertThat(instance.getScriptPatchVersion())
        .isEqualTo("patch-old");
    org.assertj.core.api.Assertions.assertThat(instance.getScriptPinEpoch()).isNull();
    verifyNoScriptPinMutation(gameInstanceRepository);
    verifyNoInteractions(tickService);
  }

  @Test
  void pinReadMapsIncoherentPersistedTupleToStableRuntimeStateFailure() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameInstance instance = validUnpinnedInstance();
    instance.setScriptPatchVersion("patch-old");
    when(repository.findById(7L)).thenReturn(Optional.of(instance));

    assertThatExceptionOfType(GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class)
        .isThrownBy(() -> service(repository, tickService).getPinnedScriptPatchVersion(1L, 7L))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                + " together");
  }

  @Test
  void convergenceReadMapsIncoherentPersistedTupleToStableRuntimeStateFailure() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameInstance instance = validUnpinnedInstance();
    instance.setScriptPatchPinnedControlPlaneRequestId("request-1");
    when(repository.findById(7L)).thenReturn(Optional.of(instance));

    assertThatExceptionOfType(GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class)
        .isThrownBy(() -> service(repository, tickService).getGameSessionPinConvergence(1L, 7L))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                + " together");
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
  void rejectsUnrecognizedExpectedPinBeforeOwnerReadOrLedgerMutation() {
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
                        .setActorPrincipal("operator")
                        .setReason("pin")
                        .setExpectedCurrentPin(
                            ExpectedCurrentPin.newBuilder().setKindValue(99).build())
                        .build()))
        .withMessage("expected_current_pin kind is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsOverlengthScriptPinInputsBeforeOwnerReadOrLedgerMutation() {
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
                        .setTargetScriptPatchVersion("x".repeat(101))
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("pin")
                        .setExpectedCurrentPin(
                            ExpectedCurrentPin.newBuilder()
                                .setKind(
                                    ExpectedCurrentPin.Kind
                                        .EXPECTED_CURRENT_PIN_KIND_EXPECT_UNPINNED)
                                .build())
                        .build()))
        .withMessage("target_script_patch_version must contain at most 100 characters");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void acceptsExactly100CharacterScriptPinTarget() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    AutomationScriptingControlPlaneClient automation =
        mock(AutomationScriptingControlPlaneClient.class);
    String target = "x".repeat(100);
    GetPublishedScriptPatchVersionResponse publication = publishedPatch(1L, 200L, 100L);
    publication =
        publication.toBuilder()
            .setScriptPatch(
                publication.getScriptPatch().toBuilder().setScriptPatchVersion(target).build())
            .build();
    when(repository.findById(7L)).thenReturn(Optional.of(validUnpinnedInstance()));
    when(gameDesign.getPublishedScriptPatchVersion(1L, target)).thenReturn(publication);
    when(automation.getScriptPatchStatus(1L, target))
        .thenReturn(
            GetScriptPatchStatusResponse.newBuilder()
                .setStatus(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY)
                .setBaseVersionId(100L)
                .build());
    when(repository.applyScriptPin(
            1L, 7L, "SET", target, "request-1", "operator", "pin", "EXPECT_UNPINNED", null))
        .thenReturn(new ScriptPinMutationResult(null, null, target, 1L, "request-1", null));

    SetPinnedScriptPatchVersionResponse response =
        newService(repository, tickService, gameDesign, automation)
            .setPinnedScriptPatchVersion(
                1L,
                7L,
                setRequest("request-1").toBuilder().setTargetScriptPatchVersion(target).build());

    assertThat(response.hasError()).isFalse();
    verify(repository)
        .applyScriptPin(
            1L, 7L, "SET", target, "request-1", "operator", "pin", "EXPECT_UNPINNED", null);
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

  private static void verifyNoScriptPinMutation(GameInstanceRepository repository) {
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
        .applyScriptPin(
            org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.nullable(String.class),
            org.mockito.Mockito.nullable(String.class),
            org.mockito.Mockito.nullable(String.class),
            org.mockito.Mockito.nullable(String.class),
            org.mockito.Mockito.nullable(String.class),
            org.mockito.Mockito.nullable(String.class),
            org.mockito.Mockito.nullable(Long.class));
  }

  private GameSessionOperatorControlPlaneService newService(
      GameInstanceRepository repository,
      TickService tickService,
      GameDesignClient gameDesign,
      AutomationScriptingControlPlaneClient automation) {
    return new GameSessionOperatorControlPlaneService(
        repository, tickService, gameDesign, automation, mock(GameSessionProperties.class));
  }

  private static String invokePinFailureMessage(
      java.lang.reflect.Method messageMethod,
      GameSessionOperatorControlPlaneService service,
      String errorCode) {
    try {
      return (String) messageMethod.invoke(service, errorCode);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError("Failed to invoke pin failure message mapping", ex);
    }
  }

  private static GameInstance validUnpinnedInstance() {
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("100");
    instance.setVersionId(100L);
    return instance;
  }

  private static GetPublishedScriptPatchVersionResponse publishedPatch(
      long tenantId, long versionId, long baseVersionId) {
    return GetPublishedScriptPatchVersionResponse.newBuilder()
        .setScriptPatch(
            PublishedScriptPatchVersion.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setScriptPatchVersion("patch-new")
                .setVersionId(versionId)
                .setBaseVersionId(baseVersionId)
                .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                .build())
        .build();
  }

  private static SetPinnedScriptPatchVersionRequest setRequest(String requestId) {
    return SetPinnedScriptPatchVersionRequest.newBuilder()
        .setTargetScriptPatchVersion("patch-new")
        .setControlPlaneRequestId(requestId)
        .setActorPrincipal("operator")
        .setReason("pin")
        .setExpectedCurrentPin(
            ExpectedCurrentPin.newBuilder()
                .setKind(ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_UNPINNED)
                .build())
        .build();
  }
}
