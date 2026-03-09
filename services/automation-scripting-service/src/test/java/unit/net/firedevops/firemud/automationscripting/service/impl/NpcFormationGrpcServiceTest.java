package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberRequest;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberResponse;
import net.firedevops.firemud.automationscripting.v1.CreateFormationRequest;
import net.firedevops.firemud.automationscripting.v1.CreateFormationResponse;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersRequest;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersResponse;
import net.firedevops.firemud.automationscripting.model.FormationType;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NpcFormationGrpcServiceTest {
  @Test
  void createFormationReturnsId() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    Mockito.when(npcService.createFormation(1L, "alpha", 2L, FormationType.LINE)).thenReturn(10L);
    NpcFormationGrpcService service = new NpcFormationGrpcService(npcService);

    AtomicReference<CreateFormationResponse> ref = new AtomicReference<>();
    service.createFormation(
        CreateFormationRequest.newBuilder()
            .setTenantId("1")
            .setName("alpha")
            .setLeaderNpcId("2")
            .setFormationType("LINE")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateFormationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("10", ref.get().getFormationId());
  }

  @Test
  void listMembersReturnsIds() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    Mockito.when(npcService.getMembers(1L, 5L)).thenReturn(List.of(2L, 3L));
    NpcFormationGrpcService service = new NpcFormationGrpcService(npcService);

    AtomicReference<ListFormationMembersResponse> ref = new AtomicReference<>();
    service.listFormationMembers(
        ListFormationMembersRequest.newBuilder().setTenantId("1").setFormationId("5").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListFormationMembersResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(List.of("2", "3"), ref.get().getNpcIdsList());
  }

  @Test
  void addMemberReturnsSuccess() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    NpcFormationGrpcService service = new NpcFormationGrpcService(npcService);

    AtomicReference<AddFormationMemberResponse> ref = new AtomicReference<>();
    service.addFormationMember(
        AddFormationMemberRequest.newBuilder()
            .setTenantId("1")
            .setFormationId("5")
            .setNpcId("2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(AddFormationMemberResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(true, ref.get().getSuccess());
  }
}
