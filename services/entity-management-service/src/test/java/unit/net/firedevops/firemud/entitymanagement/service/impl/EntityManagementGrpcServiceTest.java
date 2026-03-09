package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PingService;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class EntityManagementGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    CharacterService characterService = Mockito.mock(CharacterService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService, characterService, inventoryService, roomEntityService, meterRegistry);

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
  void pingValidationErrorReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new IllegalArgumentException("bad"));
    CharacterService characterService = Mockito.mock(CharacterService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService, characterService, inventoryService, roomEntityService, meterRegistry);

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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void pingUnexpectedErrorReturnsInternal() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new RuntimeException("boom"));
    CharacterService characterService = Mockito.mock(CharacterService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService, characterService, inventoryService, roomEntityService, meterRegistry);

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

    assertEquals(
        io.grpc.Status.INTERNAL.getCode(),
        ((io.grpc.StatusRuntimeException) err.get()).getStatus().getCode());
  }

  @Test
  void listCharactersInvalidAccountIdReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService, characterService, inventoryService, roomEntityService, meterRegistry);

    AtomicReference<net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse>
        ref = new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest.newBuilder()
            .setAccountId("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void listCharactersUnexpectedErrorReturnsInternal() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    Mockito.when(characterService.listForAccount(1L, Pageable.unpaged()))
        .thenThrow(new RuntimeException("boom"));
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService, characterService, inventoryService, roomEntityService, meterRegistry);

    AtomicReference<Throwable> err = new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest.newBuilder()
            .setAccountId("1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse value) {}

          @Override
          public void onError(Throwable t) {
            err.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals(
        Status.INTERNAL.getCode(), ((StatusRuntimeException) err.get()).getStatus().getCode());
  }
}
