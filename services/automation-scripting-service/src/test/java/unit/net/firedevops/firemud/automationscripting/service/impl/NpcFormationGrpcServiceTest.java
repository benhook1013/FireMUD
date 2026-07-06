package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.model.FormationType;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberRequest;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberResponse;
import net.firedevops.firemud.automationscripting.v1.CreateFormationRequest;
import net.firedevops.firemud.automationscripting.v1.CreateFormationResponse;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersRequest;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NpcFormationGrpcServiceTest {
  @BeforeEach
  void setSessionContext() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
  }

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void createFormationReturnsId() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    Mockito.when(npcService.createFormation(1L, "alpha", 2L, FormationType.LINE)).thenReturn(10L);
    AutomationScriptingGrpcService service = newService(npcService);

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
    AutomationScriptingGrpcService service = newService(npcService);

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
    AutomationScriptingGrpcService service = newService(npcService);

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

  @Test
  void createFormationRejectsZeroLeaderNpcIdBeforeCreate() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service = newService(npcService);

    AtomicReference<CreateFormationResponse> ref = new AtomicReference<>();
    service.createFormation(
        CreateFormationRequest.newBuilder()
            .setTenantId("1")
            .setName("alpha")
            .setLeaderNpcId("0")
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

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("leaderNpcId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(npcService);
  }

  @Test
  void addFormationMemberRejectsZeroNpcIdBeforeDispatch() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service = newService(npcService);

    AtomicReference<AddFormationMemberResponse> ref = new AtomicReference<>();
    service.addFormationMember(
        AddFormationMemberRequest.newBuilder()
            .setTenantId("1")
            .setFormationId("5")
            .setNpcId("0")
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

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("npcId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(npcService);
  }

  @Test
  void listFormationMembersRejectsZeroFormationIdBeforeLookup() {
    NpcFormationService npcService = Mockito.mock(NpcFormationService.class);
    AutomationScriptingGrpcService service = newService(npcService);

    AtomicReference<ListFormationMembersResponse> ref = new AtomicReference<>();
    service.listFormationMembers(
        ListFormationMembersRequest.newBuilder().setTenantId("1").setFormationId("0").build(),
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

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("formationId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(npcService);
  }

  private static AutomationScriptingGrpcService newService(NpcFormationService npcService) {
    return new AutomationScriptingGrpcService(
        Mockito.mock(PingService.class),
        Mockito.mock(ScriptDefinitionService.class),
        Mockito.mock(ScriptDesignDigestService.class),
        Mockito.mock(ScriptVersionService.class),
        Mockito.mock(ScriptScheduleInstanceService.class),
        Mockito.mock(ScriptEventIngressService.class),
        Mockito.mock(ScriptWorkItemRepository.class),
        npcService,
        new SimpleMeterRegistry());
  }
}
