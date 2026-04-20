package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionControlPlaneGrpcServiceTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void setPinnedScriptPatchVersionRejectsNonAdminCaller() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionControlPlaneGrpcService service =
        newService(Mockito.mock(GameInstanceRepository.class), meterRegistry);

    AtomicReference<SetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.setPinnedScriptPatchVersion(
        SetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setTargetScriptPatchVersion("patch-2")
            .setActorPrincipal("tester")
            .setReason("test")
            .setControlPlaneRequestId("req-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("PERMISSION_DENIED", responseRef.get().getError().getCode());
    assertEquals("Admin role required", responseRef.get().getError().getMessage());
    assertEquals(
        1.0,
        meterRegistry.get("grpc.app_error").tag("code", "PERMISSION_DENIED").counter().count());
  }

  @Test
  void setPinnedScriptPatchVersionAllowsAdminCaller() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptPatchPinnedAt(Instant.parse("2026-01-01T00:00:00Z"));
    instance.setScriptPatchPinnedBy("old-user");
    instance.setScriptPatchPinnedReason("old-reason");
    instance.setOwnerAccountId(99L);
    instance.setStatus("RUNNING");
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));

    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service = newService(repository);

    AtomicReference<SetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.setPinnedScriptPatchVersion(
        SetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setTargetScriptPatchVersion("patch-2")
            .setActorPrincipal("tester")
            .setReason("test")
            .setControlPlaneRequestId("req-1")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("patch-1", responseRef.get().getPreviousScriptPatchVersion());
    assertEquals("patch-2", responseRef.get().getPinnedScriptPatchVersion());
    Mockito.verify(repository).save(Mockito.any(GameInstance.class));
  }

  @Test
  void getPinnedScriptPatchVersionRequiresAuthenticatedCaller() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionControlPlaneGrpcService service =
        newService(Mockito.mock(GameInstanceRepository.class), meterRegistry);

    AtomicReference<GetPinnedScriptPatchVersionResponse> responseRef = new AtomicReference<>();
    service.getPinnedScriptPatchVersion(
        GetPinnedScriptPatchVersionRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetPinnedScriptPatchVersionResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals("", responseRef.get().getPinnedScriptPatchVersion());
    assertEquals("PERMISSION_DENIED", responseRef.get().getError().getCode());
    assertEquals("Admin role required", responseRef.get().getError().getMessage());
    assertEquals(
        1.0,
        meterRegistry.get("grpc.app_error").tag("code", "PERMISSION_DENIED").counter().count());
  }

  @Test
  void setAdmissionPointerAllowsAdminCaller() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    Instant.parse("2026-04-15T00:00:00Z"))));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<SetAdmissionPointerResponse> responseRef = new AtomicReference<>();
    service.setAdmissionPointer(
        SetAdmissionPointerRequest.newBuilder()
            .setWorldSlug("demo")
            .setWorldDisplayName("Demo World")
            .setRealmSlug("production")
            .setRealmDisplayName("Live Realm")
            .setTenantId("1")
            .setGameInstanceId("7")
            .setVisible(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-1")
            .setExpectedPointerVersion(2L)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetAdmissionPointerResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("demo", responseRef.get().getPointer().getWorldSlug());
    assertEquals(3L, responseRef.get().getPointer().getPointerVersion());
    Mockito.verify(authorityService)
        .upsertPointer(
            Mockito.argThat(mutation -> Objects.equals(mutation.expectedPointerVersion(), 2L)));
  }

  @Test
  void setAdmissionPointerRejectsStaleExpectedVersion() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.upsertPointer(Mockito.any()))
        .thenThrow(
            new AdmissionPointerVersionMismatchException(
                "expected_pointer_version does not match current pointer version"));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<SetAdmissionPointerResponse> responseRef = new AtomicReference<>();
    service.setAdmissionPointer(
        SetAdmissionPointerRequest.newBuilder()
            .setWorldSlug("demo")
            .setWorldDisplayName("Demo World")
            .setRealmSlug("production")
            .setRealmDisplayName("Live Realm")
            .setTenantId("1")
            .setGameInstanceId("7")
            .setVisible(true)
            .setRequiresCharacterSelection(false)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .setActorPrincipal("tester")
            .setReason("cutover")
            .setControlPlaneRequestId("req-2")
            .setExpectedPointerVersion(2L)
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(SetAdmissionPointerResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("POINTER_VERSION_MISMATCH", responseRef.get().getError().getCode());
  }

  @Test
  void listAdmissionPointersRequiresAdminCaller() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ListAdmissionPointersResponse> responseRef = new AtomicReference<>();
    service.listAdmissionPointers(
        ListAdmissionPointersRequest.getDefaultInstance(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListAdmissionPointersResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("PERMISSION_DENIED", responseRef.get().getError().getCode());
  }

  @Test
  void listAdmissionPointerAuditReturnsEntriesForAdminCaller() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    Mockito.when(authorityService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    7L,
                    3L,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    Instant.parse("2026-04-15T00:00:00Z")),
                new GameplayAdmissionPointerAuditEntry(
                    "demo",
                    "production",
                    "Demo World",
                    "Live Realm",
                    1L,
                    6L,
                    2L,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "previous",
                    "req-0",
                    Instant.parse("2026-04-14T00:00:00Z"))));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            Mockito.mock(RuntimeRegionStatusRepository.class),
            authorityService,
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<ListAdmissionPointerAuditResponse> responseRef = new AtomicReference<>();
    service.listAdmissionPointerAudit(
        ListAdmissionPointerAuditRequest.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(ListAdmissionPointerAuditResponse value) {
            responseRef.set(value);
          }
        });

    assertNotNull(responseRef.get());
    assertEquals(2, responseRef.get().getAuditCount());
    assertEquals("demo", responseRef.get().getAudit(0).getWorldSlug());
    assertEquals(3L, responseRef.get().getAudit(0).getPointerVersion());
  }

  @Test
  void getGameplayCommandStatusReturnsLedgerRecordForAdminCaller() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-123");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(41L);
    command.setAccountId(9L);
    command.setCharacterId(44L);
    command.setCommandName("LOOK");
    command.setSanitizedCommandText("LOOK");
    command.setRequiresSoloTick(false);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    command.setStagedAt(Instant.parse("2026-04-15T00:00:01Z"));
    command.setLastAttemptAt(Instant.parse("2026-04-15T00:00:01Z"));
    command.setAttemptCount(1);
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    Mockito.when(commandRepository.findByCommandId("cmd-123")).thenReturn(Optional.of(command));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            commandRepository,
            Mockito.mock(RuntimeRegionStatusRepository.class),
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<GetGameplayCommandStatusResponse> responseRef = new AtomicReference<>();
    service.getGameplayCommandStatus(
        GetGameplayCommandStatusRequest.newBuilder().setCommandId("cmd-123").build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetGameplayCommandStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals("cmd-123", responseRef.get().getCommand().getCommandId());
    assertEquals("STAGED", responseRef.get().getCommand().getExecutionOutcome());
    assertEquals("LOOK", responseRef.get().getCommand().getSanitizedCommandText());
    assertEquals(
        Instant.parse("2026-04-15T00:00:01Z").toEpochMilli(),
        responseRef.get().getCommand().getStagedAtMs());
  }

  @Test
  void getRuntimeOwnershipStatusReturnsDurableOwnerRecordForAdminCaller() {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(1L);
    status.setGameInstanceId(7L);
    status.setRegionEpoch(3L);
    status.setExecutorFence("fence-3");
    status.setOwnerService("game-session-service");
    status.setOwnerInstanceId("gs-1");
    status.setPaused(false);
    status.setLastCommittedTickBatchId("tb-9");
    status.setUpdatedAt(Instant.parse("2026-04-20T00:00:00Z"));
    RuntimeRegionStatusRepository repository = Mockito.mock(RuntimeRegionStatusRepository.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId(1L, 7L))
        .thenReturn(Optional.of(status));
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            Mockito.mock(GameInstanceRepository.class),
            Mockito.mock(GameplayCommandRepository.class),
            repository,
            Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry());

    AtomicReference<GetRuntimeOwnershipStatusResponse> responseRef = new AtomicReference<>();
    service.getRuntimeOwnershipStatus(
        GetRuntimeOwnershipStatusRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .build(),
        new NoopObserver<>() {
          @Override
          public void onNext(GetRuntimeOwnershipStatusResponse value) {
            responseRef.set(value);
          }
        });

    assertEquals(3L, responseRef.get().getOwnership().getRegionEpoch());
    assertEquals("fence-3", responseRef.get().getOwnership().getExecutorFence());
    assertEquals("tb-9", responseRef.get().getOwnership().getLastCommittedTickBatchId());
  }

  private static GameSessionControlPlaneGrpcService newService(GameInstanceRepository repository) {
    return newService(repository, new SimpleMeterRegistry());
  }

  private static GameSessionControlPlaneGrpcService newService(
      GameInstanceRepository repository, SimpleMeterRegistry meterRegistry) {
    return new GameSessionControlPlaneGrpcService(
        repository,
        Mockito.mock(GameplayCommandRepository.class),
        Mockito.mock(RuntimeRegionStatusRepository.class),
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class),
        Mockito.mock(TickService.class),
        meterRegistry);
  }

  private static class NoopObserver<T> implements StreamObserver<T> {
    @Override
    public void onNext(T value) {}

    @Override
    public void onError(Throwable t) {
      fail(t);
    }

    @Override
    public void onCompleted() {}
  }
}
