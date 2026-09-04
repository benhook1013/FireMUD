package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptDeadLetterReplayRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

class ScriptWorkItemServiceImplTest {
  @Test
  void replayFingerprintIsFixedLengthAndCanonicalAcrossIdOrderAndBoundaryWhitespace()
      throws Exception {
    List<String> ids =
        IntStream.rangeClosed(1, 100)
            .mapToObj(String::valueOf)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    ScriptWorkItemService.ReplayDeadLettersCommand first =
        new ScriptWorkItemService.ReplayDeadLettersCommand(
            " tenant-1 ", "", "", ids, "", 0L, 0L, 100, " request-1 ", " operator ", " retry ");
    List<String> reordered = new ArrayList<>(ids);
    Collections.reverse(reordered);
    ScriptWorkItemService.ReplayDeadLettersCommand equivalent =
        new ScriptWorkItemService.ReplayDeadLettersCommand(
            "tenant-1", "", "", reordered, "", 0L, 0L, 100, "request-1", "operator", "retry");

    Method fingerprint =
        ScriptWorkItemServiceImpl.class.getDeclaredMethod(
            "replayRequestFingerprint", ScriptWorkItemService.ReplayDeadLettersCommand.class);
    fingerprint.setAccessible(true);
    String firstDigest = (String) fingerprint.invoke(null, first);
    String equivalentDigest = (String) fingerprint.invoke(null, equivalent);

    assertThat(firstDigest).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(equivalentDigest).isEqualTo(firstDigest);
  }

  private static ScriptEventIngressAuditRepository ingressAuditRepository() {
    return Mockito.mock(ScriptEventIngressAuditRepository.class);
  }

  private static ScriptPatchInstanceRolloutProjectionService rolloutProjectionService() {
    return Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
  }

  private static AutomationAdmissionStateService admissionStateService() {
    AutomationAdmissionStateService service = Mockito.mock(AutomationAdmissionStateService.class);
    when(service.getState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 1L, "", "", "", 100L));
    when(service.findState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            java.util.Optional.of(
                new AutomationAdmissionStateService.AdmissionStateSummary(
                    "1", "game-1", "region-1", "NORMAL", 1L, "", "", "", 100L)));
    return service;
  }

  private static GameDesignControlPlaneClient gameDesignClient() {
    GameDesignControlPlaneClient client = Mockito.mock(GameDesignControlPlaneClient.class);
    when(client.getPublishedScriptPatchVersion(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setTenantId("1")
                        .setScriptPatchVersion("patch-1")
                        .setVersionId(17L)
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(150L)
                        .build())
                .build());
    when(client.getPublishedReleaseBundle(Mockito.anyString(), Mockito.eq(7L)))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-1")
                                .build())
                        .build())
                .build());
    return client;
  }

  private static ScriptPatchReadinessProjectionService readinessProjectionService() {
    return Mockito.mock(ScriptPatchReadinessProjectionService.class);
  }

  private static ScriptWorkItemService service(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptEventIngressAuditRepository ingressAuditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      ScriptOutboxProperties outboxProperties,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient) {
    return service(
        workItemRepository,
        auditRepository,
        ingressAuditRepository,
        handoffEventRepository,
        outboxProperties,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        rolloutProjectionService,
        pluginRuntimeStateService,
        gameDesignControlPlaneClient,
        readinessProjectionService());
  }

  private static ScriptWorkItemService service(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptEventIngressAuditRepository ingressAuditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      ScriptOutboxProperties outboxProperties,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      ScriptPatchReadinessProjectionService readinessProjectionService) {
    return new ScriptWorkItemServiceImpl(
        workItemRepository,
        auditRepository,
        ingressAuditRepository,
        handoffEventRepository,
        outboxProperties,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        rolloutProjectionService,
        pluginRuntimeStateService,
        gameDesignControlPlaneClient,
        readinessProjectionService,
        null,
        null,
        new SimpleMeterRegistry());
  }

  private static ScriptWorkItemService service(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptEventIngressAuditRepository ingressAuditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      ScriptOutboxProperties outboxProperties,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      MeterRegistry meterRegistry) {
    return new ScriptWorkItemServiceImpl(
        workItemRepository,
        auditRepository,
        ingressAuditRepository,
        handoffEventRepository,
        outboxProperties,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        rolloutProjectionService,
        pluginRuntimeStateService,
        gameDesignControlPlaneClient,
        readinessProjectionService,
        null,
        null,
        meterRegistry);
  }

  @Test
  void rejectsDirectReplayWhenRuntimeAuthorityUnavailableAfterTenantNormalization() {
    ScriptWorkItem item = replayableRuntimeWorkItem(90L);
    item.setScriptPinEpoch(1L);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.findById(90L)).thenReturn(Optional.of(item));
    ScriptWorkItemService service = replayService(workItemRepository);

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                " 1 ", "", "", List.of("90"), "", 0L, 0L, 10, "req-90", "", ""));

    assertThat(result.replayedCount()).isZero();
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(result.results())
        .singleElement()
        .satisfies(
            replay ->
                assertThat(replay.rejectionReason())
                    .isEqualTo("script_pin_authority_collaborator_unavailable"));
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    verify(workItemRepository).findById(90L);
  }

  @Test
  void replayCountsOnlyCanonicalRetriedOutcome() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptDeadLetterReplayRepository replayRepository =
        Mockito.mock(ScriptDeadLetterReplayRepository.class);
    when(replayRepository.insertOrGet(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptDeadLetterReplayRepository.ReplayRequest(
                    1L, invocation.getArgument(2), "RUNNING", 0L, 0L));
    when(replayRepository.findResults(1L))
        .thenReturn(
            List.of(
                new ScriptDeadLetterReplayRepository.ReplayItem(
                    90L, "retried_evaluation_unknown", "legacy_reason", 1L, 0L, 0L, 1L)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService(),
            replayRepository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            new SimpleMeterRegistry());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("90"), "", 0L, 0L, 10, "req-90", "admin", "retry"));

    assertThat(result.replayedCount()).isZero();
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(result.results())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.outcome()).isEqualTo("rejected");
              assertThat(item.rejectionReason()).isEqualTo("stage_evidence_unavailable");
              assertThat(item.failureReason()).isEmpty();
            });
  }

  @Test
  void replayAggregateCountsAreDerivedFromPerItemOutcomes() {
    ScriptDeadLetterReplayRepository replayRepository =
        Mockito.mock(ScriptDeadLetterReplayRepository.class);
    when(replayRepository.insertOrGet(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptDeadLetterReplayRepository.ReplayRequest(
                    1L, invocation.getArgument(2), "COMPLETED", 1L, 4L));
    when(replayRepository.findResults(1L))
        .thenReturn(
            List.of(
                new ScriptDeadLetterReplayRepository.ReplayItem(
                    90L, "retried_evaluation", "", 1L, 0L, 0L, 1L),
                new ScriptDeadLetterReplayRepository.ReplayItem(
                    91L, "resumed_dispatch", "", 1L, 0L, 0L, 1L),
                new ScriptDeadLetterReplayRepository.ReplayItem(
                    92L, "already_recovered", "", 1L, 0L, 0L, 1L),
                new ScriptDeadLetterReplayRepository.ReplayItem(
                    93L, "recovery_failed", "", 1L, 0L, 0L, 1L),
                new ScriptDeadLetterReplayRepository.ReplayItem(
                    94L, "rejected", "stage_evidence_unavailable", 1L, 0L, 0L, 1L)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService(),
            replayRepository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            new SimpleMeterRegistry());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1",
                "",
                "",
                List.of("90", "91", "92", "93", "94"),
                "",
                0L,
                0L,
                10,
                "req-mixed",
                "admin",
                "retry"));

    assertThat(result.replayedCount()).isEqualTo(2L);
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(result.results())
        .extracting(ScriptWorkItemService.ReplayItemResult::outcome)
        .containsExactly(
            "retried_evaluation",
            "resumed_dispatch",
            "already_recovered",
            "recovery_failed",
            "rejected");
  }

  @Test
  void rejectsTenantReplayWithoutExplicitWorkItemIds() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItemService service = replayService(workItemRepository);

    assertThatThrownBy(
            () ->
                service.replayDeadLetters(
                    new ScriptWorkItemService.ReplayDeadLettersCommand(
                        " 1 ", "", "", List.of(), "", 0L, 0L, 10, "req-91", "", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid_work_item_ids");
    verifyNoInteractions(workItemRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"game-1", " game-1 ", " "})
  @NullAndEmptySource
  void cancelsPendingPatchWorkAndUpdatesAuditOutcome(String gameInstanceId) {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(42L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setScriptPatchVersion("patch-1");
    item.setStatus("PENDING_EVALUATION");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository
            .findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
                Mockito.eq("1"),
                Mockito.eq("patch-1"),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq(List.of("PENDING_EVALUATION"))))
        .thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(42L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    long canceled =
        service.cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                " 1 ", "patch-1", gameInstanceId, " region-1 ", "req-1", "admin", "rollback"));

    assertThat(canceled).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("rollback");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("rollback");
    verify(workItemRepository).saveAll(List.of(item));
    verify(auditRepository).save(audit);
  }

  @Test
  void refreshesReadinessProjectionWhenCancelingPendingOnLoadWork() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(43L);
    item.setTenantId("1");
    item.setScriptPatchVersion("patch-1");
    item.setEventType("onLoad");
    item.setStatus("PENDING_EVALUATION");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(workItemRepository
            .findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
                "1", "patch-1", "", "", List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(43L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    long canceled =
        service.cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                "1", "patch-1", "", "", "req-1", "admin", "rollback"));

    assertThat(canceled).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    verify(readinessProjectionService).refreshFromOnLoadWorkItems("1", "patch-1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"game-1", " game-1 ", " "})
  @NullAndEmptySource
  void cancelsPendingPluginVersionWorkAndUpdatesAuditOutcome(String gameInstanceId) {
    String normalizedGameInstanceId = gameInstanceId == null ? "" : gameInstanceId.strip();
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(43L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-v1");
    item.setScriptPatchVersion("patch-1");
    item.setStatus("PENDING_EVALUATION");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository
            .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
                "1",
                "plugin-1",
                "plugin-v1",
                normalizedGameInstanceId,
                "",
                List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(43L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    long canceled =
        service.cancelPendingForPluginVersion(
            new ScriptWorkItemService.CancelPendingForPluginVersionCommand(
                " 1 ", "plugin-1", "plugin-v1", gameInstanceId, "", "req-1", "admin", ""));

    assertThat(canceled).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("operator_cancel");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("operator_cancel");
    verify(workItemRepository).saveAll(List.of(item));
    verify(auditRepository).save(audit);
  }

  @Test
  void cancellationContinuesAcrossBoundedPagesUntilNoEligibleRowsRemain() {
    List<ScriptWorkItem> firstPage =
        IntStream.rangeClosed(1, 100).mapToObj(id -> cancelableWorkItem(id, "patch-1")).toList();
    List<ScriptWorkItem> secondPage = List.of(cancelableWorkItem(101L, "patch-1"));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository
            .findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
                "tenant-1", "patch-1", "game-1", "region-1", List.of("PENDING_EVALUATION")))
        .thenReturn(firstPage, secondPage, List.of());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    long canceled =
        service.cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                "tenant-1", "patch-1", "game-1", "region-1", "req-1", "admin", "rollback"));

    assertThat(canceled).isEqualTo(101L);
    assertThat(firstPage).allSatisfy(item -> assertThat(item.getStatus()).isEqualTo("CANCELED"));
    assertThat(secondPage).allSatisfy(item -> assertThat(item.getStatus()).isEqualTo("CANCELED"));
    verify(workItemRepository, Mockito.times(2)).saveAll(Mockito.anyCollection());
    verify(workItemRepository, Mockito.times(2))
        .findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
            "tenant-1", "patch-1", "game-1", "region-1", List.of("PENDING_EVALUATION"));
  }

  private static ScriptWorkItem cancelableWorkItem(long id, String scriptPatchVersion) {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(id);
    item.setTenantId("tenant-1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setScriptPatchVersion(scriptPatchVersion);
    item.setStatus("PENDING_EVALUATION");
    return item;
  }

  @Test
  void claimsPendingItemsForEvaluationInStableOrder() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setStatus("PENDING_EVALUATION");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByStatusForUpdateOrderByCreatedAtAscIdAsc(
            "PENDING_EVALUATION", PageRequest.of(0, 10)))
        .thenReturn(List.of(item));
    when(workItemRepository.saveAll(List.of(item))).thenReturn(List.of(item));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItem> claimed = service.claimPendingForEvaluation(10);

    assertThat(claimed).containsExactly(item);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    assertThat(item.getUpdatedAt()).isNotNull();
    verify(workItemRepository).saveAll(List.of(item));
  }

  @Test
  void rejectsInvalidClaimLimit() {
    ScriptWorkItemService service =
        service(
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThatThrownBy(() -> service.claimPendingForEvaluation(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("max_items must be positive");
  }

  @Test
  void claimsPendingWorkItemsByQueuePointerIds() {
    ScriptWorkItem item = workItem("patch-1", "PENDING_EVALUATION", Instant.EPOCH);
    item.setId(99L);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByIdInAndStatusForUpdateOrderByCreatedAtAscIdAsc(
            List.of(99L, 100L), "PENDING_EVALUATION", PageRequest.of(0, 10)))
        .thenReturn(List.of(item));
    when(workItemRepository.saveAll(List.of(item))).thenReturn(List.of(item));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItem> claimed = service.claimPendingForEvaluation(List.of(99L, 100L), 10);

    assertThat(claimed).containsExactly(item);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    verify(workItemRepository).saveAll(List.of(item));
  }

  @Test
  void cleansTerminalOutboxRowsUsingConfiguredRetentionWindows() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutboxProperties properties = outboxProperties();
    when(workItemRepository.deleteByStatusAndUpdatedAtBefore(
            Mockito.eq("HANDED_OFF"), Mockito.any()))
        .thenReturn(2L);
    when(workItemRepository.deleteByStatusAndUpdatedAtBefore(Mockito.eq("CANCELED"), Mockito.any()))
        .thenReturn(3L);
    when(workItemRepository.deleteByStatusAndUpdatedAtBefore(
            Mockito.eq("DEAD_LETTERED"), Mockito.any()))
        .thenReturn(5L);
    when(workItemRepository.countByStatus("DEAD_LETTERED")).thenReturn(100000L);
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            properties,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.TerminalCleanupResult result = service.cleanupTerminalWorkItems();

    assertThat(result.handedOffDeleted()).isEqualTo(2L);
    assertThat(result.canceledDeleted()).isEqualTo(3L);
    assertThat(result.deadLetteredDeleted()).isEqualTo(5L);
    assertThat(result.totalDeleted()).isEqualTo(10L);
    verify(workItemRepository)
        .deleteByStatusAndUpdatedAtBefore(Mockito.eq("HANDED_OFF"), Mockito.any());
    verify(workItemRepository)
        .deleteByStatusAndUpdatedAtBefore(Mockito.eq("CANCELED"), Mockito.any());
    verify(workItemRepository)
        .deleteByStatusAndUpdatedAtBefore(Mockito.eq("DEAD_LETTERED"), Mockito.any());
  }

  @Test
  void updatesRetentionBlockedRowsGaugeToCurrentBlockedPopulation() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.countTerminalRowsBlockedByEvidence(Mockito.anyString(), Mockito.any()))
        .thenReturn(2L, 2L, 2L, 0L, 0L, 0L);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService(),
            meterRegistry);

    service.cleanupTerminalWorkItems();
    assertThat(meterRegistry.get("automation_retention_blocked_rows").gauge().value())
        .isEqualTo(6.0);

    service.cleanupTerminalWorkItems();
    assertThat(meterRegistry.get("automation_retention_blocked_rows").gauge().value()).isZero();
  }

  @Test
  void recordsDisposedRetentionCountInInjectedMeterRegistry() {
    ScriptDeadLetterReplayRepository replayRepository =
        Mockito.mock(ScriptDeadLetterReplayRepository.class);
    when(replayRepository.deleteExpiredResults(Mockito.any(), Mockito.any())).thenReturn(2L);
    when(replayRepository.deleteExpiredRequests(Mockito.any(), Mockito.any())).thenReturn(3L);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService(),
            replayRepository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            meterRegistry);

    service.cleanupTerminalWorkItems();

    assertThat(meterRegistry.get("automation_retention_disposed_total").counter().count())
        .isEqualTo(5.0);
  }

  @Test
  void deletesOldestDeadLettersWhenRowCapIsExceeded() {
    ScriptWorkItem old = new ScriptWorkItem();
    old.setId(7L);
    old.setTenantId("tenant-1");
    old.setStatus("DEAD_LETTERED");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutboxProperties properties = outboxProperties();
    properties.setDeadLetterMaxRows(1);
    when(workItemRepository.countByStatus("DEAD_LETTERED")).thenReturn(2L);
    when(workItemRepository.findByStatusOrderByUpdatedAtAscIdAscAfter(
            "DEAD_LETTERED", null, null, 500))
        .thenReturn(List.of(old));
    when(workItemRepository.deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 7L))
        .thenReturn(true);
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            properties,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.TerminalCleanupResult result = service.cleanupTerminalWorkItems();

    assertThat(result.deadLetteredDeleted()).isEqualTo(1L);
    verify(workItemRepository).deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 7L);
  }

  @Test
  void rowCapCleanupSkipsHeldOldestRowsToReachEligibleEvidence() {
    ScriptWorkItem held = new ScriptWorkItem();
    held.setId(7L);
    held.setTenantId("tenant-1");
    held.setStatus("DEAD_LETTERED");
    ScriptWorkItem eligible = new ScriptWorkItem();
    eligible.setId(8L);
    eligible.setTenantId("tenant-1");
    eligible.setStatus("DEAD_LETTERED");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutboxProperties properties = outboxProperties();
    properties.setDeadLetterMaxRows(1);
    when(workItemRepository.countByStatus("DEAD_LETTERED")).thenReturn(3L);
    when(workItemRepository.findByStatusOrderByUpdatedAtAscIdAscAfter(
            "DEAD_LETTERED", null, null, 500))
        .thenReturn(List.of(held, eligible));
    when(workItemRepository.deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 7L))
        .thenReturn(false);
    when(workItemRepository.deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 8L))
        .thenReturn(true);
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            properties,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThat(service.cleanupTerminalWorkItems().deadLetteredDeleted()).isEqualTo(1L);
    verify(workItemRepository).deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 7L);
    verify(workItemRepository).deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 8L);
  }

  @Test
  void summarizesPatchStatusFromReadinessProjection() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(readinessProjectionService.getProjection("1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-1",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_ONLOAD_RUNNING,
                    "tenant_readiness_running",
                    "",
                    200L)));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    Optional<ScriptWorkItemService.PatchStatusSummary> status =
        service.getPatchStatus("1", "patch-1");

    assertThat(status).isPresent();
    assertThat(status.get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_ONLOAD_RUNNING);
    assertThat(status.get().statusReason()).isEqualTo("tenant_readiness_running");
    assertThat(status.get().lastChangedAtMs()).isEqualTo(200L);
    assertThat(status.get().baseVersionId()).isEqualTo(7L);
    assertThat(status.get().abilitySchemaDigest()).isEqualTo("ability-1");
    assertThat(status.get().publication().versionId()).isEqualTo(17L);
    assertThat(status.get().publication().publicationState())
        .isEqualTo(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_PUBLISHED);
  }

  @Test
  void listsPatchStatusesWithFilters() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(readinessProjectionService.listProjections("1"))
        .thenReturn(
            List.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-ready",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY,
                    "ready_for_tenant",
                    "",
                    200L),
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-failed",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED,
                    "runtime_region_scope_advanced",
                    "",
                    300L)));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    List<ScriptWorkItemService.PatchStatusSummary> statuses =
        service.listPatchStatuses("1", ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED, 250L, 0L);

    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).scriptPatchVersion()).isEqualTo("patch-failed");
    assertThat(statuses.get(0).status()).isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED);
    assertThat(statuses.get(0).statusReason()).isEqualTo("runtime_region_scope_advanced");
    assertThat(statuses.get(0).baseVersionId()).isEqualTo(7L);
    assertThat(statuses.get(0).abilitySchemaDigest()).isEqualTo("ability-1");
    assertThat(statuses.get(0).publication().versionId()).isEqualTo(17L);
  }

  @Test
  void summarizesRolledBackProjectedPatchStatusWithSpecificCancelReason() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(readinessProjectionService.getProjection("1", "patch-rollback"))
        .thenReturn(
            Optional.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-rollback",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_ROLLED_BACK,
                    "rollback_epoch_advanced",
                    "",
                    300L)));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    Optional<ScriptWorkItemService.PatchStatusSummary> status =
        service.getPatchStatus("1", "patch-rollback");

    assertThat(status).isPresent();
    assertThat(status.get().status()).isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_ROLLED_BACK);
    assertThat(status.get().statusReason()).isEqualTo("rollback_epoch_advanced");
  }

  @Test
  void listsOnlyProjectedPatchStatusesWhenReadinessProjectionServiceIsPresent() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(readinessProjectionService.listProjections("1"))
        .thenReturn(
            List.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-projected",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY,
                    "ready_for_tenant",
                    "",
                    500L)));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    List<ScriptWorkItemService.PatchStatusSummary> statuses =
        service.listPatchStatuses("1", ScriptPatchStatus.SCRIPT_PATCH_STATUS_UNSPECIFIED, 0L, 0L);

    assertThat(statuses).hasSize(1);
    assertThat(statuses)
        .extracting(ScriptWorkItemService.PatchStatusSummary::scriptPatchVersion)
        .containsExactly("patch-projected");
    assertThat(statuses)
        .extracting(ScriptWorkItemService.PatchStatusSummary::status)
        .containsExactly(ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY);
    verify(workItemRepository, never()).findDistinctScriptPatchVersionsByTenantId("1");
    verify(workItemRepository, never()).findByTenantIdAndScriptPatchVersion("1", "patch-legacy");
  }

  @Test
  void returnsEmptyPatchStatusWhenProjectionServiceHasNoProjectionForPatch() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(readinessProjectionService.getProjection("1", "patch-legacy"))
        .thenReturn(Optional.empty());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    Optional<ScriptWorkItemService.PatchStatusSummary> status =
        service.getPatchStatus("1", "patch-legacy");

    assertThat(status).isEmpty();
    verify(workItemRepository, never()).findByTenantIdAndScriptPatchVersion("1", "patch-legacy");
  }

  @Test
  void reportsAutomationDrainStatusForScopedWorkItems() {
    ScriptWorkItem pending = workItem("patch-1", "PENDING_EVALUATION", Instant.ofEpochMilli(200));
    pending.setTenantId("1");
    pending.setGameInstanceId("game-1");
    pending.setRegionId("region-1");
    ScriptWorkItem evaluating = workItem("patch-1", "EVALUATING", Instant.ofEpochMilli(250));
    evaluating.setTenantId("1");
    evaluating.setGameInstanceId("game-1");
    evaluating.setRegionId("region-1");
    evaluating.setCreatedAt(Instant.ofEpochMilli(120));
    ScriptWorkItem inflight = workItem("patch-1", "HANDOFF_IN_FLIGHT", Instant.ofEpochMilli(260));
    inflight.setTenantId("1");
    inflight.setGameInstanceId("game-1");
    inflight.setRegionId("region-1");
    inflight.setCreatedAt(Instant.ofEpochMilli(140));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of(evaluating, inflight, pending));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", "region-1");

    assertThat(summary.tenantId()).isEqualTo("1");
    assertThat(summary.gameInstanceId()).isEqualTo("game-1");
    assertThat(summary.regionId()).isEqualTo("region-1");
    assertThat(summary.statePresent()).isTrue();
    assertThat(summary.admissionMode()).isEqualTo("NORMAL");
    assertThat(summary.admissionEpoch()).isEqualTo(1L);
    assertThat(summary.activeExecutionCount()).isEqualTo(2L);
    assertThat(summary.oldestActiveExecutionStartedAtMs()).isEqualTo(120L);
    assertThat(summary.pendingCancelableWorkItemCount()).isEqualTo(1L);
    assertThat(summary.observedAtMs()).isPositive();
  }

  @Test
  void normalAutomationDrainStatusCountsOnlyCurrentAdmissionEpoch() {
    ScriptWorkItem currentEvaluating =
        workItem("patch-1", "EVALUATING", Instant.ofEpochMilli(120L));
    currentEvaluating.setAdmissionEpoch(2L);
    currentEvaluating.setCreatedAt(Instant.ofEpochMilli(120L));
    ScriptWorkItem currentPending =
        workItem("patch-1", "PENDING_EVALUATION", Instant.ofEpochMilli(130L));
    currentPending.setAdmissionEpoch(2L);
    currentPending.setCreatedAt(Instant.ofEpochMilli(130L));
    ScriptWorkItem priorHandoff =
        workItem("patch-1", "HANDOFF_IN_FLIGHT", Instant.ofEpochMilli(100L));
    priorHandoff.setAdmissionEpoch(1L);
    priorHandoff.setCreatedAt(Instant.ofEpochMilli(100L));
    ScriptWorkItem futureEvaluating = workItem("patch-1", "EVALUATING", Instant.ofEpochMilli(90L));
    futureEvaluating.setAdmissionEpoch(3L);
    futureEvaluating.setCreatedAt(Instant.ofEpochMilli(90L));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionStateService.findState("1", "game-1", "region-1"))
        .thenReturn(
            Optional.of(
                new AutomationAdmissionStateService.AdmissionStateSummary(
                    "1", "game-1", "region-1", "NORMAL", 2L, "", "", "", 100L)));
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of(currentEvaluating, currentPending, priorHandoff, futureEvaluating));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", "region-1");

    assertThat(summary.activeExecutionCount()).isEqualTo(1L);
    assertThat(summary.oldestActiveExecutionStartedAtMs()).isEqualTo(120L);
    assertThat(summary.pendingCancelableWorkItemCount()).isEqualTo(1L);
  }

  @Test
  void pausedAutomationDrainStatusCountsLegacyAndPriorEpochsOnly() {
    ScriptWorkItem legacyPending =
        workItem("patch-1", "PENDING_EVALUATION", Instant.ofEpochMilli(130L));
    legacyPending.setAdmissionEpoch(0L);
    legacyPending.setCreatedAt(Instant.ofEpochMilli(130L));
    ScriptWorkItem priorEvaluating = workItem("patch-1", "EVALUATING", Instant.ofEpochMilli(110L));
    priorEvaluating.setAdmissionEpoch(1L);
    priorEvaluating.setCreatedAt(Instant.ofEpochMilli(110L));
    ScriptWorkItem priorHandoff =
        workItem("patch-1", "HANDOFF_IN_FLIGHT", Instant.ofEpochMilli(120L));
    priorHandoff.setAdmissionEpoch(-1L);
    priorHandoff.setCreatedAt(Instant.ofEpochMilli(120L));
    ScriptWorkItem currentEvaluating = workItem("patch-1", "EVALUATING", Instant.ofEpochMilli(90L));
    currentEvaluating.setAdmissionEpoch(2L);
    currentEvaluating.setCreatedAt(Instant.ofEpochMilli(90L));
    ScriptWorkItem futurePending =
        workItem("patch-1", "PENDING_EVALUATION", Instant.ofEpochMilli(80L));
    futurePending.setAdmissionEpoch(3L);
    futurePending.setCreatedAt(Instant.ofEpochMilli(80L));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionStateService.findState("1", "game-1", "region-1"))
        .thenReturn(
            Optional.of(
                new AutomationAdmissionStateService.AdmissionStateSummary(
                    "1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", 2L, "", "", "", 100L)));
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(
            List.of(
                legacyPending, priorEvaluating, priorHandoff, currentEvaluating, futurePending));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", "region-1");

    assertThat(summary.activeExecutionCount()).isEqualTo(2L);
    assertThat(summary.oldestActiveExecutionStartedAtMs()).isEqualTo(110L);
    assertThat(summary.pendingCancelableWorkItemCount()).isEqualTo(1L);
  }

  @Test
  void reportsZeroedAutomationDrainStatusWhenScopedWorkIsEmpty() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1", "game-1", "", List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", "");

    assertThat(summary.regionId()).isEmpty();
    assertThat(summary.activeExecutionCount()).isZero();
    assertThat(summary.oldestActiveExecutionStartedAtMs()).isZero();
    assertThat(summary.pendingCancelableWorkItemCount()).isZero();
  }

  @Test
  void reportsCanonicalDiagnosticWhenAdmissionStateIsMissing() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionStateService.findState("1", "game-1", "region-1")).thenReturn(Optional.empty());
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service(
                workItemRepository,
                auditRepository,
                ingressAuditRepository(),
                Mockito.mock(ScriptHandoffEventRepository.class),
                outboxProperties(),
                admissionStateService,
                Mockito.mock(ScriptPatchPinProjectionService.class),
                rolloutProjectionService(),
                Mockito.mock(PluginRuntimeStateService.class),
                gameDesignClient())
            .getAutomationDrainStatus("1", "game-1", "region-1");

    assertThat(summary.statePresent()).isFalse();
    assertThat(summary.admissionMode()).isEqualTo("NORMAL");
    assertThat(summary.admissionEpoch()).isZero();
    assertThat(summary.controlPlaneRequestId()).isEmpty();
    assertThat(summary.targetMode()).isEmpty();
    assertThat(summary.outcome()).isEqualTo("NOT_FOUND");
    assertThat(summary.requestFingerprint()).isEmpty();
    assertThat(summary.acknowledgedAtMs()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"   ", "\u2003"})
  void normalizesBlankAutomationDrainRegionToExactUnscopedRow(String regionId) {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService = admissionStateService();
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1", "game-1", "", List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", regionId);

    assertThat(summary.regionId()).isEmpty();
    verify(admissionStateService).findState("1", "game-1", "");
    verify(workItemRepository)
        .findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1", "game-1", "", List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT"));
  }

  @Test
  void normalizesPaddedAutomationDrainRegionForAdmissionWorkItemQueriesAndSummary() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService = admissionStateService();
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", " region-1 ");

    assertThat(summary.regionId()).isEqualTo("region-1");
    verify(admissionStateService).findState("1", "game-1", "region-1");
    verify(workItemRepository)
        .findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT"));
  }

  @Test
  void normalizesPaddedAutomationDrainGameInstanceForAdmissionWorkItemQueriesAndSummary() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService = admissionStateService();
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", " game-1 ", "region-1");

    assertThat(summary.gameInstanceId()).isEqualTo("game-1");
    verify(admissionStateService).findState("1", "game-1", "region-1");
    verify(workItemRepository)
        .findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT"));
  }

  @Test
  void normalizesPaddedAutomationDrainTenantForAdmissionWorkItemQueriesAndSummary() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService = admissionStateService();
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus(" 1 ", " game-1 ", " region-1 ");

    assertThat(summary.tenantId()).isEqualTo("1");
    assertThat(summary.gameInstanceId()).isEqualTo("game-1");
    assertThat(summary.regionId()).isEqualTo("region-1");
    verify(admissionStateService).findState("1", "game-1", "region-1");
    verify(workItemRepository)
        .findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankAutomationDrainGameInstanceBeforeScopedLookups(String gameInstanceId) {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService = admissionStateService();
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThatThrownBy(() -> service.getAutomationDrainStatus("1", gameInstanceId, "region-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("game_instance_id is required");
    verify(admissionStateService, never())
        .getState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    verify(admissionStateService, never())
        .findState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    verifyNoInteractions(workItemRepository);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankAutomationDrainTenantBeforeScopedLookups(String tenantId) {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationAdmissionStateService admissionStateService = admissionStateService();
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThatThrownBy(() -> service.getAutomationDrainStatus(tenantId, "game-1", "region-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenant_id is required");
    verify(admissionStateService, never())
        .getState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    verify(admissionStateService, never())
        .findState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    verifyNoInteractions(workItemRepository);
  }

  @Test
  void getsInstanceRolloutStatusFromRuntimePin() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of());
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-1", "req-1", 150L, 151L, 0L, false, "", 0L, "", "",
                        "")),
                "",
                ""));
    when(rolloutProjectionService.getProjection("1", "game-1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED,
                    "runtime_pin_matches_patch",
                    150L,
                    151L,
                    0L,
                    false,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "",
                        0L,
                        0L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_UNSPECIFIED,
                        0L,
                        "",
                        ""))));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService,
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getPatchInstanceRolloutStatus("1", "game-1", "patch-1");

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED);
    assertThat(summary.get().statusReason()).isEqualTo("runtime_pin_matches_patch");
    assertThat(summary.get().projectionLagMs()).isZero();
    assertThat(summary.get().projectionStale()).isFalse();
    assertThat(summary.get().publication().versionId()).isEqualTo(17L);
  }

  @Test
  void fallsBackToStaleLocalInstanceRolloutWhenRuntimeUnavailable() {
    ScriptWorkItem canceled = workItem("patch-1", "CANCELED", Instant.ofEpochMilli(200));
    canceled.setTenantId("1");
    canceled.setGameInstanceId("game-1");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of(canceled));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(Optional.empty(), "", ""));
    when(rolloutProjectionService.getProjection("1", "game-1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
                    "projection_lag_exceeded",
                    200L,
                    200L,
                    5_100L,
                    true,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "",
                        0L,
                        0L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_UNSPECIFIED,
                        0L,
                        "",
                        ""))));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService,
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getPatchInstanceRolloutStatus("1", "game-1", "patch-1");

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK);
    assertThat(summary.get().statusReason()).isEqualTo("projection_lag_exceeded");
    assertThat(summary.get().projectionStale()).isTrue();
    assertThat(summary.get().publication().versionId()).isEqualTo(17L);
  }

  @Test
  void listsInstanceRolloutsFromDistinctPairsAndRuntimeFilter() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptWorkItem ready = workItem("patch-1", "HANDED_OFF", Instant.ofEpochMilli(250));
    ready.setTenantId("1");
    ready.setGameInstanceId("game-1");
    ScriptWorkItemRepository.ScriptPatchInstanceProjection pair =
        new ScriptWorkItemRepository.ScriptPatchInstanceProjection() {
          @Override
          public String getGameInstanceId() {
            return "game-1";
          }

          @Override
          public String getScriptPatchVersion() {
            return "patch-1";
          }
        };
    when(workItemRepository.findDistinctInstancePatchPairs("1", "", "")).thenReturn(List.of(pair));
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of(ready));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-2", "req-2", 260L, 261L, 0L, false, "", 0L, "", "",
                        "")),
                "",
                ""));
    when(rolloutProjectionService.listProjections(
            "1",
            "",
            "",
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
            0L,
            0L))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
                    "runtime_pin_differs_from_patch",
                    260L,
                    261L,
                    0L,
                    false,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "",
                        0L,
                        0L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_UNSPECIFIED,
                        0L,
                        "",
                        ""))));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService,
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.PatchInstanceRolloutSummary> summaries =
        service.listPatchInstanceRollouts(
            "1",
            "",
            "",
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
            0L,
            0L);

    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).gameInstanceId()).isEqualTo("game-1");
    assertThat(summaries.get(0).scriptPatchVersion()).isEqualTo("patch-1");
    assertThat(summaries.get(0).rolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK);
    assertThat(summaries.get(0).publication().versionId()).isEqualTo(17L);
  }

  @Test
  void listsDeadLettersWithBoundedFilters() {
    ScriptWorkItem deadLetter = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    deadLetter.setId(99L);
    deadLetter.setTenantId("1");
    deadLetter.setGameInstanceId("game-1");
    deadLetter.setRegionId("region-1");
    deadLetter.setRegionEpoch(12L);
    deadLetter.setEntityId("entity-1");
    deadLetter.setScriptId("script-1");
    deadLetter.setEventType("onCommand");
    deadLetter.setScriptEventId("event-1");
    deadLetter.setCancelReason("STALE_TIMELINE");
    deadLetter.setSourceKind("GAMEPLAY_EVENT");
    deadLetter.setSourceState("WORK_ITEM_PERSISTED");
    deadLetter.setPluginId("plugin-1");
    deadLetter.setPluginVersionId("plugin-v1");
    deadLetter.setCreatedAt(Instant.ofEpochMilli(100));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findDeadLetters(
            "1", "DEAD_LETTERED", "game-1", "patch-1", PageRequest.of(0, 25)))
        .thenReturn(List.of(deadLetter));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.DeadLetterSummary> deadLetters =
        service.listDeadLetters("1", "game-1", "patch-1", 25);

    assertThat(deadLetters).hasSize(1);
    assertThat(deadLetters.get(0).workItemId()).isEqualTo("99");
    assertThat(deadLetters.get(0).sourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(deadLetters.get(0).sourceState()).isEqualTo("WORK_ITEM_PERSISTED");
    assertThat(deadLetters.get(0).pluginId()).isEqualTo("plugin-1");
    assertThat(deadLetters.get(0).pluginVersionId()).isEqualTo("plugin-v1");
    assertThat(deadLetters.get(0).reason()).isEqualTo("STALE_TIMELINE");
    assertThat(deadLetters.get(0).updatedAtMs()).isEqualTo(300L);
    assertThat(deadLetters.get(0).publication().versionId()).isEqualTo(17L);
  }

  @Test
  void collapsesPartialRoutingBundleInDeadLetterSummary() {
    ScriptWorkItem deadLetter = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    deadLetter.setId(99L);
    deadLetter.setTenantId("1");
    deadLetter.setGameInstanceId("game-1");
    deadLetter.setRegionId("region-1");
    deadLetter.setRegionEpoch(12L);
    deadLetter.setEntityId("entity-1");
    deadLetter.setPlayableStateScope("SHARED");
    deadLetter.setWorldSlug("demo");
    deadLetter.setPointerVersion("17");
    deadLetter.setScriptId("script-1");
    deadLetter.setEventType("onCommand");
    deadLetter.setScriptEventId("event-1");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.findDeadLetters(
            "1", "DEAD_LETTERED", "game-1", "patch-1", PageRequest.of(0, 25)))
        .thenReturn(List.of(deadLetter));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.DeadLetterSummary> deadLetters =
        service.listDeadLetters("1", "game-1", "patch-1", 25);

    assertThat(deadLetters).hasSize(1);
    assertThat(deadLetters.get(0).worldSlug()).isBlank();
    assertThat(deadLetters.get(0).realmSlug()).isBlank();
    assertThat(deadLetters.get(0).pointerVersion()).isBlank();
  }

  @Test
  void listsHandoffEventsWithBoundedFilters() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("event-1");
    event.setTenantId("1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setScriptId("script-1");
    event.setPluginId("plugin-1");
    event.setPluginVersionId("plugin-v1");
    event.setWorkItemId(99L);
    event.setCommandOrdinal(0);
    event.setAutomationDispatchId("workItem:99#0");
    event.setGameSessionCommandId("command-1");
    event.setTargetGameInstanceId("game-2");
    event.setTargetRegionId("region-2");
    event.setTargetRegionEpoch(17L);
    event.setRemoteCoordinatorId("remote-coordinator:workItem:99#0");
    event.setRemoteFollowupId("remote-followup:workItem:99#0");
    event.setTargetEntityId("target-1");
    event.setSourceKind("SCHEDULE_TIMER");
    event.setSourceState("SCHEDULE_DUE_CLAIMED");
    event.setSourceOrdinal(5000L);
    event.setSourceDueAtMs(5000L);
    event.setEmittedCommandText("LOOK AT old chest");
    event.setHandoffOutcome("enqueued");
    event.setHandoffReason("game_session_accepted");
    event.setObservedAt(Instant.ofEpochMilli(300L));
    when(handoffEventRepository.findEvents(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq("patch-1"),
            Mockito.eq(99L),
            Mockito.eq("enqueued"),
            Mockito.eq("game-2"),
            Mockito.eq("region-2"),
            Mockito.eq(17L),
            Mockito.eq("remote-coordinator:workItem:99#0"),
            Mockito.eq("remote-followup:workItem:99#0"),
            Mockito.eq("script-1"),
            Mockito.eq("plugin-1"),
            Mockito.eq("workItem:99#0"),
            Mockito.eq("command-1"),
            Mockito.eq("target-1"),
            Mockito.eq("SHARED"),
            Mockito.eq("demo"),
            Mockito.eq("production"),
            Mockito.eq("17"),
            Mockito.eq("SCHEDULE_TIMER"),
            Mockito.eq("SCHEDULE_DUE_CLAIMED"),
            Mockito.any(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(List.of(event));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            handoffEventRepository,
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.HandoffEventSummary> events =
        service.listHandoffEvents(
            "1",
            "game-1",
            "patch-1",
            "99",
            "enqueued",
            "game-2",
            "region-2",
            17L,
            "remote-coordinator:workItem:99#0",
            "remote-followup:workItem:99#0",
            "script-1",
            "plugin-1",
            "workItem:99#0",
            "command-1",
            "target-1",
            "SHARED",
            "demo",
            "production",
            "17",
            "SCHEDULE_TIMER",
            "SCHEDULE_DUE_CLAIMED",
            10L,
            20L,
            25);

    assertThat(events).hasSize(1);
    assertThat(events.get(0).automationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(events.get(0).gameSessionCommandId()).isEqualTo("command-1");
    assertThat(events.get(0).targetGameInstanceId()).isEqualTo("game-2");
    assertThat(events.get(0).targetRegionId()).isEqualTo("region-2");
    assertThat(events.get(0).targetRegionEpoch()).isEqualTo(17L);
    assertThat(events.get(0).remoteCoordinatorId()).isEqualTo("remote-coordinator:workItem:99#0");
    assertThat(events.get(0).sourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(events.get(0).sourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(events.get(0).sourceOrdinal()).isEqualTo(5000L);
    assertThat(events.get(0).emittedCommandText()).isEqualTo("LOOK AT old chest");
    assertThat(events.get(0).handoffOutcome()).isEqualTo("enqueued");
    assertThat(events.get(0).publication().versionId()).isEqualTo(17L);
  }

  @Test
  void normalizesPartialRoutingBundleFilterAndSummaryForHandoffEvents() {
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("event-1");
    event.setTenantId("1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setScriptId("script-1");
    event.setWorkItemId(99L);
    event.setTargetEntityId("target-1");
    event.setPlayableStateScope("SHARED");
    event.setWorldSlug("demo");
    event.setPointerVersion("17");
    event.setHandoffOutcome("enqueued");
    event.setHandoffReason("game_session_accepted");
    event.setObservedAt(Instant.ofEpochMilli(300L));
    when(handoffEventRepository.findEvents(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq("patch-1"),
            Mockito.eq(99L),
            Mockito.eq("enqueued"),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq(0L),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq("script-1"),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq("target-1"),
            Mockito.eq("SHARED"),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.eq(""),
            Mockito.any(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(List.of(event));
    ScriptWorkItemService service =
        service(
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            handoffEventRepository,
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.HandoffEventSummary> events =
        service.listHandoffEvents(
            "1",
            "game-1",
            "patch-1",
            "99",
            "enqueued",
            "",
            "",
            0L,
            "",
            "",
            "script-1",
            "",
            "",
            "",
            "target-1",
            "SHARED",
            "demo",
            "",
            "17",
            "",
            "",
            10L,
            20L,
            25);

    assertThat(events).hasSize(1);
    assertThat(events.get(0).worldSlug()).isBlank();
    assertThat(events.get(0).realmSlug()).isBlank();
    assertThat(events.get(0).pointerVersion()).isBlank();
  }

  @ParameterizedTest
  @NullAndEmptySource
  void rejectsReplayForMissingGameInstanceId(String gameInstanceId) {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(77L);
    item.setTenantId("1");
    item.setGameInstanceId(gameInstanceId);
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEntityId("entity-1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptEventId("event-1");
    item.setSourceService("game-session-service");
    item.setCreatedAt(Instant.ofEpochMilli(100));
    item.setCancelReason("GAME_SESSION_UNAVAILABLE");
    ScriptEventAudit audit = new ScriptEventAudit();
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventIngressAuditRepository ingressAuditRepository = ingressAuditRepository();
    when(workItemRepository.findById(77L)).thenReturn(Optional.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(77L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            pluginRuntimeStateService,
            gameDesignClient());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("77"), "", 0L, 0L, 10, "req-1", "admin", "retry"));

    assertThat(result.replayedCount()).isZero();
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(result.results())
        .singleElement()
        .satisfies(
            replay -> assertThat(replay.rejectionReason()).isEqualTo("runtime_scope_missing"));
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
  }

  @Test
  void rejectsPluginOwnedDeadLetterWhenRuntimeAuthorityIsUnavailable() {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(78L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEntityId("entity-1");
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-v1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptEventId("event-2");
    item.setSourceService("game-session-service");
    item.setCreatedAt(Instant.ofEpochMilli(100));
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findById(78L)).thenReturn(Optional.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService(),
            pluginRuntimeStateService,
            gameDesignClient());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("78"), "", 0L, 0L, 10, "req-1", "admin", "retry"));

    assertThat(result.replayedCount()).isZero();
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    verify(pluginRuntimeStateService, Mockito.never()).getStatus("1", "game-1", "plugin-1");
  }

  @Test
  void rejectsReplayWhenPinnedPatchDoesNotMatch() {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(77L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEntityId("entity-1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptEventId("event-1");
    item.setSourceService("game-session-service");
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventIngressAuditRepository ingressAuditRepository = ingressAuditRepository();
    when(workItemRepository.findById(77L)).thenReturn(Optional.of(item));
    when(auditRepository.findByWorkItemId(77L)).thenReturn(Optional.empty());
    when(ingressAuditRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                3L,
                "entity-1",
                "",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-2", "req-2", 600L, 601L, 0L, false, "", 0L, "", "",
                        "")),
                "",
                ""));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("77"), "", 0L, 0L, 10, "req-77", "", ""));

    assertThat(result.replayedCount()).isEqualTo(0L);
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
  }

  @Test
  void rejectsOnLoadDeadLetterWithCanonicalReplayReason() {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(88L);
    item.setTenantId("1");
    item.setEventType("onLoad");
    item.setScriptId("boot-script");
    item.setScriptEventId("onload:1:patch-1:boot-script");
    item.setCreatedAt(Instant.ofEpochMilli(100));
    item.setCancelReason("definition_invalid");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(workItemRepository.findById(88L)).thenReturn(Optional.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(88L)).thenReturn(Optional.of(audit));
    when(readinessProjectionService.getProjection("1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-1",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED,
                    "onload_failed",
                    "",
                    900L)));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("88"), "", 0L, 0L, 10, "req-1", "admin", "retry"));

    assertThat(result.replayedCount()).isZero();
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(result.results())
        .singleElement()
        .satisfies(
            replay -> {
              assertThat(replay.outcome()).isEqualTo("rejected");
              assertThat(replay.rejectionReason()).isEqualTo("onload_not_replayable");
            });
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    verify(readinessProjectionService, Mockito.never())
        .refreshFromOnLoadWorkItems(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void rejectsReplayForSupersededOnLoadDeadLetter() {
    ScriptWorkItem item = workItem("patch-old", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(89L);
    item.setTenantId("1");
    item.setEventType("onLoad");
    item.setScriptId("boot-script");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(workItemRepository.findById(89L)).thenReturn(Optional.of(item));
    when(auditRepository.findByWorkItemId(89L)).thenReturn(Optional.empty());
    when(readinessProjectionService.getProjection("1", "patch-old"))
        .thenReturn(
            Optional.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-old",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_SUPERSEDED,
                    "superseded_by_newer_patch",
                    "patch-new",
                    901L)));
    ScriptWorkItemService service =
        service(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("89"), "", 0L, 0L, 10, "req-89", "", ""));

    assertThat(result.replayedCount()).isEqualTo(0L);
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    verify(readinessProjectionService, Mockito.never())
        .refreshFromOnLoadWorkItems(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void replayRejectsNonPositiveWorkItemIdBeforeRepositoryRead() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItemService service =
        service(
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThatThrownBy(
            () ->
                service.replayDeadLetters(
                    new ScriptWorkItemService.ReplayDeadLettersCommand(
                        "1", "", "", List.of("0"), "", 0L, 0L, 10, "", "", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("work_item_id must be positive");
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void replayRejectsDistinctTextualIdsThatParseToTheSameWorkItem() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItemService service =
        service(
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThatThrownBy(
            () ->
                service.replayDeadLetters(
                    new ScriptWorkItemService.ReplayDeadLettersCommand(
                        "1", "", "", List.of("01", "1"), "", 0L, 0L, 10, "req-duplicate", "", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid_work_item_ids");
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void replayEligibilityRejectsRuntimeScopeMismatchBeforeFenceComparison() throws Exception {
    ScriptWorkItem item = replayableRuntimeWorkItem(92L);
    item.setScriptPinEpoch(1L);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("other-tenant")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
                        .setRegionId("region-1")
                        .setRegionEpoch(3L)
                        .build())
                .build());
    ScriptWorkItemServiceImpl service =
        new ScriptWorkItemServiceImpl(
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService(),
            Mockito.mock(ScriptDeadLetterReplayRepository.class),
            gameSessionControlPlaneClient,
            new SimpleMeterRegistry());

    Method eligibility =
        ScriptWorkItemServiceImpl.class.getDeclaredMethod(
            "replayEligibilityReason", ScriptWorkItem.class, Map.class);
    eligibility.setAccessible(true);

    assertThat(eligibility.invoke(service, item, new HashMap<>()))
        .isEqualTo("runtime_scope_changed");
  }

  private static ScriptWorkItem workItem(String patchVersion, String status, Instant updatedAt) {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("1");
    item.setScriptPatchVersion(patchVersion);
    item.setStatus(status);
    item.setUpdatedAt(updatedAt);
    return item;
  }

  private static ScriptWorkItem replayableRuntimeWorkItem(long id) {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(100L));
    item.setId(id);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptEventId("event-" + id);
    return item;
  }

  private static ScriptWorkItemService replayService(ScriptWorkItemRepository workItemRepository) {
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-1", "", 100L, 100L, 0L, false, "", 0L, "", "", "")),
                "",
                ""));
    return service(
        workItemRepository,
        Mockito.mock(ScriptEventAuditRepository.class),
        ingressAuditRepository(),
        Mockito.mock(ScriptHandoffEventRepository.class),
        outboxProperties(),
        admissionStateService(),
        pinProjectionService,
        rolloutProjectionService(),
        Mockito.mock(PluginRuntimeStateService.class),
        gameDesignClient());
  }

  private static ScriptOutboxProperties outboxProperties() {
    return new ScriptOutboxProperties();
  }
}
