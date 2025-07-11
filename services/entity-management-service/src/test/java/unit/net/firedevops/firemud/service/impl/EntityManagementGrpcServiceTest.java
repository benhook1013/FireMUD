package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.service.CharacterService;
import net.firedevops.firemud.service.PingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EntityManagementGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(pingService, characterService);

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
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(pingService, characterService);

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
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(pingService, characterService);

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
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(pingService, characterService);

    AtomicReference<net.firedevops.firemud.entitymanagement.v1.ListCharactersResponse> ref =
        new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersRequest.newBuilder()
            .setAccountId("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersResponse value) {
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
    Mockito.when(characterService.listForAccount(1L)).thenThrow(new RuntimeException("boom"));
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(pingService, characterService);

    AtomicReference<Throwable> err = new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersRequest.newBuilder()
            .setAccountId("1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersResponse value) {}

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
