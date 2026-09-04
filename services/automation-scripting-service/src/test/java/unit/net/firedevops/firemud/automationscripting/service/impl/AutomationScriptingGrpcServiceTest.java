package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusResponse;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateResponse;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressRequest;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressResponse;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptRequest;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationScriptingGrpcServiceTest {
  @BeforeEach
  void setSessionContext() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
  }

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void getDraftDesignDigestSupportsVersionScope() {
    PingService pingService = Mockito.mock(PingService.class);
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    ScriptDesignDigestService scriptDesignDigestService =
        Mockito.mock(ScriptDesignDigestService.class);
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    ScriptEventIngressService ingressService = Mockito.mock(ScriptEventIngressService.class);
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    Mockito.when(scriptDesignDigestService.getDraftDesignDigestForVersion("1", "7"))
        .thenReturn(
            new ScriptDesignDigestService.ScriptDraftDesignDigest(
                "1", "7", "version:7", "digest-script", 1));
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
            Mockito.mock(ScriptScheduleInstanceService.class),
            ingressService,
            Mockito.mock(ScriptWorkItemRepository.class),
            formationService,
            new SimpleMeterRegistry());

    AtomicReference<GetDraftDesignDigestResponse> ref = new AtomicReference<>();
    service.getDraftDesignDigest(
        GetDraftDesignDigestRequest.newBuilder().setTenantId("1").setVersionId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetDraftDesignDigestResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("7", ref.get().getScopeValue());
    assertEquals("version:7", ref.get().getAppliedCommitId());
  }

  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    ScriptDesignDigestService scriptDesignDigestService =
        Mockito.mock(ScriptDesignDigestService.class);
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    ScriptEventIngressService ingressService = Mockito.mock(ScriptEventIngressService.class);
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
            Mockito.mock(ScriptScheduleInstanceService.class),
            ingressService,
            Mockito.mock(ScriptWorkItemRepository.class),
            formationService,
            new SimpleMeterRegistry());

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void validationErrorReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new IllegalArgumentException("bad"));
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    ScriptDesignDigestService scriptDesignDigestService =
        Mockito.mock(ScriptDesignDigestService.class);
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    ScriptEventIngressService ingressService = Mockito.mock(ScriptEventIngressService.class);
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
            Mockito.mock(ScriptScheduleInstanceService.class),
            ingressService,
            Mockito.mock(ScriptWorkItemRepository.class),
            formationService,
            new SimpleMeterRegistry());

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    ErrorDetail error = ref.get().getError();
    assertEquals("INVALID_ARGUMENT", error.getCode());
  }

  @Test
  void unexpectedErrorReturnsInternalResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new RuntimeException("boom"));
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    ScriptDesignDigestService scriptDesignDigestService =
        Mockito.mock(ScriptDesignDigestService.class);
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    ScriptEventIngressService ingressService = Mockito.mock(ScriptEventIngressService.class);
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
            Mockito.mock(ScriptScheduleInstanceService.class),
            ingressService,
            Mockito.mock(ScriptWorkItemRepository.class),
            formationService,
            new SimpleMeterRegistry());

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void triggerScriptEventReturnsAdmissionOutcome() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressService ingressService = Mockito.mock(ScriptEventIngressService.class);
    Mockito.when(ingressService.admit(Mockito.any()))
        .thenReturn(
            new ScriptEventIngressService.TriggerAdmission(
                true,
                TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name(),
                "admitted_handlers_resolved",
                2));
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(ScriptDefinitionService.class),
            Mockito.mock(ScriptDesignDigestService.class),
            Mockito.mock(ScriptVersionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            ingressService,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(NpcFormationService.class),
            new SimpleMeterRegistry());

    AtomicReference<TriggerScriptEventResponse> ref = new AtomicReference<>();
    service.triggerScriptEvent(
        TriggerScriptEventRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .setRegionEpoch(7)
            .setEntityId("entity-1")
            .setScriptId("script-1")
            .setEventType("onCommand")
            .setEventSchemaVersion("v1")
            .setScriptPatchVersion("patch-1")
            .setScriptEventId("event-1")
            .setReadSnapshotToken("snapshot-1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(TriggerScriptEventResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(true, ref.get().getAdmitted());
    assertEquals(
        TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED,
        ref.get().getAdmissionOutcome());
    assertEquals(2, ref.get().getResolvedHandlerCount());
  }

  @Test
  void triggerScriptEventReturnsInvalidArgumentAsTransportError() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressService ingressService = Mockito.mock(ScriptEventIngressService.class);
    Mockito.when(ingressService.admit(Mockito.any()))
        .thenThrow(new IllegalArgumentException("payload_json exceeds input envelope limit"));
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(ScriptDefinitionService.class),
            Mockito.mock(ScriptDesignDigestService.class),
            Mockito.mock(ScriptVersionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            ingressService,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(NpcFormationService.class),
            new SimpleMeterRegistry());

    AtomicReference<TriggerScriptEventResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    service.triggerScriptEvent(
        TriggerScriptEventRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(TriggerScriptEventResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable t) {
            error.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNull(response.get());
    assertEquals(Status.INVALID_ARGUMENT.getCode(), Status.fromThrowable(error.get()).getCode());
    assertEquals(
        "payload_json exceeds input envelope limit",
        Status.fromThrowable(error.get()).getDescription());
  }

  @Test
  void getScriptStatusUsesWorkItemOutbox() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    Mockito.when(
            workItemRepository.existsByTenantIdAndScriptIdAndStatusIn(
                "1", "guard-script", List.of("PENDING_EVALUATION")))
        .thenReturn(true);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(ScriptDefinitionService.class),
            Mockito.mock(ScriptDesignDigestService.class),
            Mockito.mock(ScriptVersionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            Mockito.mock(ScriptEventIngressService.class),
            workItemRepository,
            Mockito.mock(NpcFormationService.class),
            new SimpleMeterRegistry());
    AtomicReference<GetScriptStatusResponse> ref = new AtomicReference<>();

    service.getScriptStatus(
        GetScriptStatusRequest.newBuilder().setTenantId("1").setScriptName("guard-script").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetScriptStatusResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(true, ref.get().getQueued());
    assertEquals(false, ref.get().getRunning());
  }

  @Test
  void notifyScriptVersionUpdateReturnsAppErrorForInvalidScheduleMetadata() {
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    Mockito.doThrow(new IllegalArgumentException("schedule_interval_ticks_required"))
        .when(versionService)
        .notifyUpdate("1", "patch-1", List.of("guard-script"));
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(ScriptDefinitionService.class),
            Mockito.mock(ScriptDesignDigestService.class),
            versionService,
            Mockito.mock(ScriptScheduleInstanceService.class),
            Mockito.mock(ScriptEventIngressService.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(NpcFormationService.class),
            new SimpleMeterRegistry());
    AtomicReference<NotifyScriptVersionUpdateResponse> ref = new AtomicReference<>();

    service.notifyScriptVersionUpdate(
        NotifyScriptVersionUpdateRequest.newBuilder()
            .setTenantId("1")
            .setScriptPatchVersion("patch-1")
            .addAffectedScripts("guard-script")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyScriptVersionUpdateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(false, ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void observeRuntimeTickProgressDelegatesToScheduleInstanceService() {
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(
            scheduleInstanceService.observeRuntimeTickProgress(
                new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                    "1", "game-1", "region-1", 12L, 100L, 5_000L)))
        .thenReturn(new ScriptScheduleInstanceService.RuntimeTickProgressResult(2, 1, 3));
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(ScriptDefinitionService.class),
            Mockito.mock(ScriptDesignDigestService.class),
            Mockito.mock(ScriptVersionService.class),
            scheduleInstanceService,
            Mockito.mock(ScriptEventIngressService.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(NpcFormationService.class),
            new SimpleMeterRegistry());
    AtomicReference<ObserveRuntimeTickProgressResponse> ref = new AtomicReference<>();

    service.observeRuntimeTickProgress(
        ObserveRuntimeTickProgressRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setTickId(100L)
            .setObservedAtMs(5_000L)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ObserveRuntimeTickProgressResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(2, ref.get().getUpdatedScheduleCount());
    assertEquals(1, ref.get().getFiredScheduleCount());
    assertEquals(3, ref.get().getTruncatedFiringCount());
  }

  @Test
  void updateScriptRejectsZeroTenantIdBeforeUpdate() {
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            Mockito.mock(PingService.class),
            scriptService,
            Mockito.mock(ScriptDesignDigestService.class),
            Mockito.mock(ScriptVersionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            Mockito.mock(ScriptEventIngressService.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(NpcFormationService.class),
            new SimpleMeterRegistry());

    AtomicReference<UpdateScriptResponse> ref = new AtomicReference<>();
    service.updateScript(
        UpdateScriptRequest.newBuilder()
            .setTenantId("0")
            .setName("guard-script")
            .setVersion("v1")
            .setDefinition("{}")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(UpdateScriptResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(scriptService);
  }
}
