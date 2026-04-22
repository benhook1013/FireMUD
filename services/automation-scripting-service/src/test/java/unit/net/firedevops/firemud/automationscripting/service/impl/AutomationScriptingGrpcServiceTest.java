package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
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
            ingressService,
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
            ingressService,
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
            ingressService,
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
            ingressService,
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
            ingressService,
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
}
