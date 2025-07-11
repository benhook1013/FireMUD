package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.stream.Collectors;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.service.CharacterService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

/** Simple gRPC service exposing the Ping RPC. */
@GRpcService
public class EntityManagementGrpcService
    extends EntityManagementServiceGrpc.EntityManagementServiceImplBase {
  private final PingService pingService;
  private final CharacterService characterService;

  public EntityManagementGrpcService(PingService pingService, CharacterService characterService) {
    this.pingService = pingService;
    this.characterService = characterService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    try {
      String msg = pingService.ping();
      PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void listCharactersByAccount(
      ListCharactersRequest request, StreamObserver<ListCharactersResponse> responseObserver) {
    try {
      long accountId = Long.parseLong(request.getAccountId());
      var characters =
          characterService.listForAccount(accountId).stream()
              .map(this::toProto)
              .collect(Collectors.toList());
      ListCharactersResponse response =
          ListCharactersResponse.newBuilder().addAllCharacters(characters).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      ListCharactersResponse response =
          ListCharactersResponse.newBuilder()
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListCharactersResponse response =
          ListCharactersResponse.newBuilder()
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  private Character toProto(CharacterDto dto) {
    return Character.newBuilder()
        .setId(String.valueOf(dto.id()))
        .setTenantId(String.valueOf(dto.tenantId()))
        .setAccountId(String.valueOf(dto.accountId()))
        .setName(dto.name())
        .setLevel(dto.level())
        .setExperience(dto.experience())
        .setStrength(dto.strength())
        .setAgility(dto.agility())
        .setIntelligence(dto.intelligence())
        .setStamina(dto.stamina())
        .setHealth(dto.health())
        .setMana(dto.mana())
        .build();
  }
}
