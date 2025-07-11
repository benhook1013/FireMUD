package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import net.firedevops.firemud.gamesession.v1.StartSessionRequest;
import net.firedevops.firemud.gamesession.v1.StartSessionResponse;
import net.firedevops.firemud.service.FeatureFlagService;
import net.firedevops.firemud.service.GameInstanceService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.TickService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TickService tickService = Mockito.mock(TickService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService, gameInstanceService, featureFlagService, tickService, meterRegistry);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<PingResponse>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void startSessionReturnsId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TickService tickService = Mockito.mock(TickService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.dto.StartSessionRequest.class)))
        .thenReturn(new GameInstanceDto(1L, 1L, "v1", 0L, "RUNNING"));
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService, gameInstanceService, featureFlagService, tickService, meterRegistry);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder().setTenantId("1").setVersionId("v1").build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("1", ref.get().getSessionId());
  }
}
