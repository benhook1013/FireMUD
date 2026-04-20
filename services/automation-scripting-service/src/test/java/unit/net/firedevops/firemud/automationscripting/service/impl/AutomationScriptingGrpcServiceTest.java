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
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
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
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
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
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
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
    NpcFormationService formationService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(
            pingService,
            scriptService,
            scriptDesignDigestService,
            versionService,
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
}
