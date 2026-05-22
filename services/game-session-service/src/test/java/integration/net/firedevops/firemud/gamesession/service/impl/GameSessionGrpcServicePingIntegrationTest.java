package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionGrpcServicePingIntegrationTest {
  @Test
  void pingEndpointReturnsSuccessErrorDetail() throws Exception {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);

    GameSessionGrpcService grpcService =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            (tenantId, viewerAccountId, accountIds) -> java.util.List.of(),
            new GameplayWorldCatalog(new GameplayCatalogProperties()),
            tickService,
            meterRegistry,
            ipLimiter);

    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName).directExecutor().addService(grpcService).build();
    server.start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    try {
      GameSessionServiceGrpc.GameSessionServiceBlockingStub stub =
          GameSessionServiceGrpc.newBlockingStub(channel);
      PingResponse response = stub.ping(PingRequest.getDefaultInstance());
      assertEquals("pong", response.getMessage());
      assertEquals("OK", response.getError().getCode());
      assertEquals("pong", response.getError().getMessage());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
