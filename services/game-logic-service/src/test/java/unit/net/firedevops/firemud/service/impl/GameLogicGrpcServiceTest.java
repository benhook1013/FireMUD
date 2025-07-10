package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.logic.command.DefaultCommandParser;
import net.firedevops.firemud.logic.command.SimpleCommandProcessor;
import net.firedevops.firemud.logic.event.EventDispatcher;
import net.firedevops.firemud.logic.script.NoOpScriptingHook;
import net.firedevops.firemud.logic.service.CommandServiceImpl;
import net.firedevops.firemud.service.PingService;
import org.junit.jupiter.api.Test;

class GameLogicGrpcServiceTest {
  @Test
  void pingEndpointReturnsPong() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    GameLogicGrpcService service = new GameLogicGrpcService(pingService, commandService);

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
}
