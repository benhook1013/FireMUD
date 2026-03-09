package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationScriptingGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(pingService, scriptService, versionService);

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
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(pingService, scriptService, versionService);

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
  void unexpectedErrorReturnsInternal() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new RuntimeException("boom"));
    ScriptDefinitionService scriptService = Mockito.mock(ScriptDefinitionService.class);
    ScriptVersionService versionService = Mockito.mock(ScriptVersionService.class);
    AutomationScriptingGrpcService service =
        new AutomationScriptingGrpcService(pingService, scriptService, versionService);

    AtomicReference<Throwable> err = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {}

          @Override
          public void onError(Throwable t) {
            err.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(err.get());
    StatusRuntimeException ex = (StatusRuntimeException) err.get();
    assertEquals(Status.INTERNAL.getCode(), ex.getStatus().getCode());
  }
}
