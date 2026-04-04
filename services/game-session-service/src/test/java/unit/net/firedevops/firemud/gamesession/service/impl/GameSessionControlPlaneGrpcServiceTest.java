package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionControlPlaneGrpcServiceTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void setPinnedScriptPatchVersionRejectsNonAdminCaller() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    GameSessionControlPlaneGrpcService service =
        newService(Mockito.mock(GameInstanceRepository.class));

    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                service.setPinnedScriptPatchVersion(
                    SetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setTargetScriptPatchVersion("patch-2")
                        .setActorPrincipal("tester")
                        .setReason("test")
                        .setControlPlaneRequestId("req-1")
                        .build(),
                    new NoopObserver<>()));

    assertEquals(Status.PERMISSION_DENIED.getCode(), ex.getStatus().getCode());
  }

  @Test
  void setPinnedScriptPatchVersionAllowsAdminCaller() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptPatchPinnedAt(Instant.parse("2026-01-01T00:00:00Z"));
    instance.setScriptPatchPinnedBy("old-user");
    instance.setScriptPatchPinnedReason("old-reason");
    instance.setOwnerAccountId(99L);
    instance.setStatus("RUNNING");
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service = newService(repository);

    AtomicReference<SetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.setPinnedScriptPatchVersion(
        SetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setTargetScriptPatchVersion("patch-2")
            .setActorPrincipal("tester")
            .setReason("test")
            .setControlPlaneRequestId("req-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("patch-1", responseRef.get().getPreviousScriptPatchVersion());
    assertEquals("patch-2", responseRef.get().getPinnedScriptPatchVersion());
    Mockito.verify(repository).save(Mockito.any(GameInstance.class));
  }

  @Test
  void getPinnedScriptPatchVersionRequiresAuthenticatedCaller() {
    GameSessionControlPlaneGrpcService service =
        newService(Mockito.mock(GameInstanceRepository.class));

    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                service.getPinnedScriptPatchVersion(
                    GetPinnedScriptPatchVersionRequest.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .build(),
                    new NoopObserver<>()));

    assertEquals(Status.PERMISSION_DENIED.getCode(), ex.getStatus().getCode());
  }

  private static GameSessionControlPlaneGrpcService newService(GameInstanceRepository repository) {
    return new GameSessionControlPlaneGrpcService(
        repository, Mockito.mock(TickService.class), new SimpleMeterRegistry());
  }

  private static class NoopObserver<T> implements StreamObserver<T> {
    @Override
    public void onNext(T value) {}

    @Override
    public void onError(Throwable t) {
      fail(t);
    }

    @Override
    public void onCompleted() {}
  }
}
