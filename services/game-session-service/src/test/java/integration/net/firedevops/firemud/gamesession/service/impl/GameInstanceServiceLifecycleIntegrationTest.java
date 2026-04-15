package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "spring.profiles.active=test",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0",
      "game-session.dev-isolated=false",
      "firemud.database.enabled=false",
      "firemud.redis.enabled=false",
      "spring.data.redis.repositories.enabled=false"
    })
@ActiveProfiles("test")
@Import({
  NoGrpcServerTestConfiguration.class,
  GameInstanceServiceLifecycleIntegrationTest.Config.class
})
class GameInstanceServiceLifecycleIntegrationTest {
  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:h2:mem:game-session-lifecycle-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
  }

  @Autowired private GameInstanceRepository repository;
  @Autowired private GameInstanceServiceImpl service;

  @MockitoBean private GameLogicClient gameLogicClient;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private WorldManagementClient worldManagementClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private AccountClient accountClient;
  @MockitoBean private SessionStateService sessionStateService;
  @MockitoBean private SessionContextService sessionContextService;
  @MockitoBean private CommandService commandService;
  @MockitoBean private SagaRunner sagaRunner;
  @MockitoBean private SharedSettingsAuthorityReader sharedSettingsAuthorityReader;
  @MockitoBean private LookCacheService lookCacheService;
  @MockitoBean private ScreenBufferService screenBufferService;
  @MockitoBean private ConflictTracker conflictTracker;

  @MockitoBean
  private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

  @MockitoBean private GameInstanceMapper gameInstanceMapper;

  @MockitoBean
  private org.springframework.grpc.server.lifecycle.GrpcServerLifecycle grpcServerLifecycle;

  @BeforeEach
  void setUp() throws Exception {
    repository.deleteAll();
    doNothing().when(sagaRunner).run(any());
    when(gameInstanceMapper.toDto(any(GameInstance.class)))
        .thenAnswer(
            invocation -> {
              GameInstance entity = invocation.getArgument(0);
              return new GameInstanceDto(
                  entity.getId(),
                  entity.getTenantId(),
                  entity.getRuntimeVersion(),
                  entity.getScriptPatchVersion(),
                  entity.getGameTemplateId(),
                  entity.getLaunchDescriptorId(),
                  entity.getVersionId(),
                  entity.getReleaseBundleId(),
                  entity.getVersionStateEpoch(),
                  entity.getGenerationConfigRevision(),
                  entity.getOwnerAccountId(),
                  entity.getStatus());
            });
    when(gameDesignClient.resolveLaunchDescriptor(42L, 7L, "cp-1"))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse.newBuilder()
                .setLaunchDescriptor(
                    net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                        .setLaunchDescriptorId("ld-1")
                        .setTenantId("42")
                        .setGameTemplateId(7L)
                        .setControlPlaneRequestId("cp-1")
                        .setVersionId(11L)
                        .setScriptPatchVersion("patch-1")
                        .setRuntimeFlagsJson("{}")
                        .setGenerationConfigRevision("genrev-11")
                        .setVersionStateEpoch(77L)
                        .setReleaseBundleId(77L)
                        .setPublishedReleaseBundleRef("prb:42:11:77")
                        .build())
                .build());
    when(gameDesignClient.resolveLaunchDescriptor(42L, 7L, "cp-2"))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse.newBuilder()
                .setLaunchDescriptor(
                    net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                        .setLaunchDescriptorId("ld-2")
                        .setTenantId("42")
                        .setGameTemplateId(7L)
                        .setControlPlaneRequestId("cp-2")
                        .setVersionId(12L)
                        .setScriptPatchVersion("patch-2")
                        .setRuntimeFlagsJson("{}")
                        .setGenerationConfigRevision("genrev-12")
                        .setVersionStateEpoch(78L)
                        .setReleaseBundleId(78L)
                        .setPublishedReleaseBundleRef("prb:42:12:78")
                        .build())
                .build());
    when(gameDesignClient.getPublishedReleaseBundle(42L, 11L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setGenerationConfigRevision("genrev-11")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(42L, 11L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse.newBuilder()
                .setVersionState(
                    net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot.newBuilder()
                        .setTenantId("42")
                        .setVersionId(11L)
                        .setVersionState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(77L)
                        .setUpdatedAt("2026-04-15T10:00:00")
                        .build())
                .build());
    when(gameDesignClient.getPublishedReleaseBundle(42L, 12L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
                        .setId(78L)
                        .setVersionId(12L)
                        .setGenerationConfigRevision("genrev-12")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(42L, 12L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse.newBuilder()
                .setVersionState(
                    net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot.newBuilder()
                        .setTenantId("42")
                        .setVersionId(12L)
                        .setVersionState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(78L)
                        .setUpdatedAt("2026-04-15T10:00:00")
                        .build())
                .build());
    when(worldManagementClient.prepareWorldInstance(
            any(Long.class),
            any(Long.class),
            any(Long.class),
            any(),
            any(),
            any(Long.class),
            any(),
            any(),
            any(),
            any(Long.class),
            any(),
            any(Long.class)))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("42")
                        .setGameInstanceId("1")
                        .setGameTemplateId("7")
                        .setControlPlaneRequestId("cp")
                        .setLaunchDescriptorId("ld-cp")
                        .setVersionId("11")
                        .setReleaseBundleId("77")
                        .setGenerationConfigRevision("genrev-11")
                        .setPublishedReleaseBundleRef("prb:42:11:77")
                        .setVersionStateEpoch(77L)
                        .setLifecycleEpoch(1L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING)
                        .build())
                .build());
    when(worldManagementClient.activatePreparedWorldInstance(
            any(Long.class), any(Long.class), any(Long.class)))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                .newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("42")
                        .setGameInstanceId("1")
                        .setLifecycleEpoch(2L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                        .build())
                .build());
    when(worldManagementClient.failPreparedWorldInstance(
            any(Long.class), any(Long.class), any(Long.class), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("42")
                        .setGameInstanceId("1")
                        .setLifecycleEpoch(2L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION)
                        .build())
                .build());
    when(worldManagementClient.getWorldInstanceLifecycle(any(Long.class), any(Long.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse
                    .newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1)))
                            .setLifecycleEpoch(2L)
                            .setStatus(
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                            .build())
                    .build());
    when(worldManagementClient.terminateWorldInstance(
            any(Long.class), any(Long.class), any(Long.class), any(), any()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse
                    .newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1)))
                            .setLifecycleEpoch(((Long) invocation.getArgument(2)) + 1L)
                            .setStatus(
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED)
                            .build())
                    .build());
  }

  @Test
  void startSessionRollsBackWhenStatePropagationFails() {
    doThrow(new IllegalStateException("state propagation failed"))
        .when(sessionStateService)
        .saveState(any());

    assertThatThrownBy(() -> service.startSession(new StartSessionRequest(42L, 7L, "cp-1", 100L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("state propagation failed");

    assertThat(repository.findAll()).isEmpty();
    verify(sessionStateService).saveState(any());
  }

  @Test
  void stopSessionRollsBackWhenStatePropagationFails() {
    GameInstance instance = new GameInstance();
    instance.setTenantId(42L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-1");
    instance.setOwnerAccountId(100L);
    instance.setStatus("RUNNING");
    instance = repository.saveAndFlush(instance);
    long instanceId = instance.getId();

    doThrow(new IllegalStateException("state propagation failed"))
        .when(sessionStateService)
        .deleteState(42L, instanceId);

    assertThatThrownBy(() -> service.stopSession(instanceId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("state propagation failed");

    assertThat(repository.findById(instanceId)).isPresent();
    assertThat(repository.findById(instanceId).orElseThrow().getStatus()).isEqualTo("RUNNING");
    verify(sessionStateService).deleteState(42L, instanceId);
  }

  @Test
  void restartSessionRollsBackWhenStatePropagationFails() {
    GameInstance instance = new GameInstance();
    instance.setTenantId(42L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-1");
    instance.setOwnerAccountId(100L);
    instance.setStatus("STOPPED");
    instance = repository.saveAndFlush(instance);
    long instanceId = instance.getId();

    doThrow(new IllegalStateException("state propagation failed"))
        .when(sessionStateService)
        .saveState(any());

    assertThatThrownBy(() -> service.restartSession(instanceId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("state propagation failed");

    assertThat(repository.findById(instanceId)).isPresent();
    assertThat(repository.findById(instanceId).orElseThrow().getStatus()).isEqualTo("STOPPED");
    verify(sessionStateService).saveState(any());
  }

  @Test
  void replacingExistingSessionRestoresPriorRunningSessionWhenNewStartFails() {
    GameInstance existing = new GameInstance();
    existing.setTenantId(42L);
    existing.setRuntimeVersion("1.0.0");
    existing.setScriptPatchVersion("patch-1");
    existing.setOwnerAccountId(100L);
    existing.setStatus("RUNNING");
    existing = repository.saveAndFlush(existing);
    long existingId = existing.getId();

    doThrow(new IllegalStateException("state propagation failed"))
        .when(sessionStateService)
        .saveState(any());

    assertThatThrownBy(
            () -> service.startSession(new StartSessionRequest(42L, 7L, "cp-2", 100L), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("state propagation failed");

    assertThat(repository.findAll()).hasSize(1);
    GameInstance restored = repository.findById(existingId).orElseThrow();
    assertThat(restored.getStatus()).isEqualTo("RUNNING");
    assertThat(restored.getRuntimeVersion()).isEqualTo("1.0.0");
    assertThat(restored.getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(restored.getOwnerAccountId()).isEqualTo(100L);
  }

  @Test
  void replacingExistingSessionTerminatesPriorWorldBeforeFinalizingReplacement() {
    GameInstance existing = new GameInstance();
    existing.setTenantId(42L);
    existing.setRuntimeVersion("1.0.0");
    existing.setScriptPatchVersion("patch-1");
    existing.setOwnerAccountId(100L);
    existing.setStatus("RUNNING");
    existing = repository.saveAndFlush(existing);
    long existingId = existing.getId();

    GameInstanceDto started =
        service.startSession(new StartSessionRequest(42L, 7L, "cp-2", 100L), true);

    assertThat(started.status()).isEqualTo("RUNNING");
    assertThat(repository.findById(existingId)).isPresent();
    assertThat(repository.findById(existingId).orElseThrow().getStatus()).isEqualTo("STOPPED");
    verify(worldManagementClient).getWorldInstanceLifecycle(42L, existingId);
    verify(worldManagementClient)
        .terminateWorldInstance(any(Long.class), eq(existingId), any(Long.class), any(), any());
  }

  @TestConfiguration
  static class Config {
    @Bean
    SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    DevIsolatedProperties devIsolatedProperties() {
      return new DevIsolatedProperties(false);
    }
  }
}
