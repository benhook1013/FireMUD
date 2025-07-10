package net.firedevops.firemud.service.impl;

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
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void listCharactersByAccount(
      ListCharactersRequest request, StreamObserver<ListCharactersResponse> responseObserver) {
    var characters =
        characterService.listForAccount(Long.valueOf(request.getAccountId())).stream()
            .map(this::toProto)
            .collect(Collectors.toList());
    ListCharactersResponse response =
        ListCharactersResponse.newBuilder().addAllCharacters(characters).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
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
