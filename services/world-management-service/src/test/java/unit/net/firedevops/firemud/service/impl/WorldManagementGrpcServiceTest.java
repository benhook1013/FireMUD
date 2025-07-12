package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.mapper.RoomMapper;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.RoomService;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class WorldManagementGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    RoomService roomService = Mockito.mock(RoomService.class);
    RoomMapper mapper = Mappers.getMapper(RoomMapper.class);
    var worldEventService = Mockito.mock(net.firedevops.firemud.service.WorldEventService.class);
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(pingService, roomService, mapper, worldEventService);

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
}
