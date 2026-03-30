package net.firedevops.firemud.gamelogic.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamelogic.logic.command.DefaultCommandParser;
import net.firedevops.firemud.gamelogic.logic.command.SimpleCommandProcessor;
import net.firedevops.firemud.gamelogic.logic.event.EventDispatcher;
import net.firedevops.firemud.gamelogic.logic.script.NoOpScriptingHook;
import net.firedevops.firemud.gamelogic.logic.service.CommandServiceImpl;
import net.firedevops.firemud.gamelogic.service.CommunicationAggregationService;
import net.firedevops.firemud.gamelogic.service.LookAggregationService;
import net.firedevops.firemud.gamelogic.service.MoveAggregationService;
import net.firedevops.firemud.gamelogic.service.PingService;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandResponse;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameLogicGrpcServiceTest {
  @Test
  void pingEndpointReturnsPong() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    MoveResult moveResult = MoveResult.newBuilder().setSuccess(true).build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            new SimpleMeterRegistry());

    AtomicReference<PingResponse> holder = new AtomicReference<>();
    service.ping(
        PingRequest.newBuilder().build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", holder.get().getMessage());
  }

  @Test
  void executeCommandReturnsInvalidArgument() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    MoveResult moveResult = MoveResult.newBuilder().setSuccess(true).build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            new SimpleMeterRegistry());

    AtomicReference<ExecuteCommandResponse> holder = new AtomicReference<>();
    service.executeCommand(
        ExecuteCommandRequest.newBuilder().setCommand("foo").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ExecuteCommandResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("Unknown action", holder.get().getResult());
    assertEquals("UNKNOWN_COMMAND", holder.get().getError().getCode());
    assertEquals("Command not recognized", holder.get().getError().getMessage());
  }

  @Test
  void resolveMoveReturnsDestinationLook() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    MoveResult moveResult = MoveResult.newBuilder().setSuccess(true).build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            new SimpleMeterRegistry());

    AtomicReference<MoveResult> holder = new AtomicReference<>();
    service.resolveMove(
        MoveRequest.newBuilder().setDirection("NORTH").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(MoveResult value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    Mockito.verify(moveAggregationService).resolve(any());
    assertTrue(holder.get().getSuccess());
  }
}
