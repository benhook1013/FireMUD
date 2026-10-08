package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "spring.profiles.active=test",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0",
      "firemud.database.enabled=true",
      "firemud.redis.enabled=false",
      "spring.data.redis.repositories.enabled=false",
      "spring.flyway.enabled=true"
    })
@ActiveProfiles("test")
@Import({
  NoGrpcServerTestConfiguration.class,
  GameInstanceServiceLifecycleIntegrationTest.Config.class
})
class GameInstanceServiceLifecycleIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_session_service");
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
                  entity.getScriptPatchBaseVersionId(),
                  entity.getScriptPinEpoch(),
                  entity.getScriptPatchPinnedControlPlaneRequestId(),
                  entity.getGameTemplateId(),
                  entity.getLaunchDescriptorId(),
                  entity.getVersionId(),
                  entity.getReleaseBundleId(),
                  entity.getVersionStateEpoch(),
                  entity.getGenerationConfigRevision(),
                  entity.getRemapSetId(),
                  entity.getOwnerAccountId(),
                  entity.getStatus());
            });
    when(gameDesignClient.resolveLaunchDescriptor(42L, 7L, "cp-1"))
        .thenReturn(deniedNumericLaunchResponse());
    when(gameDesignClient.resolveLaunchDescriptor(42L, 7L, "cp-2"))
        .thenReturn(deniedNumericLaunchResponse());
    when(worldManagementClient.getWorldInstanceLifecycle(anyLong(), anyLong()))
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
            anyLong(), anyLong(), anyLong(), anyString(), anyString()))
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
  void numericStartIsDeniedBeforeStateOrWorldMutation() {
    assertThatThrownBy(() -> service.startSession(new StartSessionRequest(42L, 7L, "cp-1", 100L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FAILED_PRECONDITION");

    assertThat(repository.findAll()).isEmpty();
    verifyNoInteractions(sessionStateService, worldManagementClient);
  }

  @Test
  void stopSessionRollsBackWhenStatePropagationFails() {
    GameInstance instance = new GameInstance();
    instance.setTenantId(42L);
    instance.setRuntimeVersion("1.0.0");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptPinEpoch(1L);
    instance.setScriptPatchPinnedControlPlaneRequestId("pin-request-1");
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
    instance.setScriptPatchBaseVersionId(77L);
    instance.setScriptPinEpoch(1L);
    instance.setScriptPatchPinnedControlPlaneRequestId("pin-request-1");
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
    ArgumentCaptor<GameInstanceDto> state = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(sessionStateService).saveState(state.capture());
    assertThat(state.getValue().scriptPatchBaseVersionId()).isEqualTo(77L);
    assertThat(repository.findById(instanceId).orElseThrow().getScriptPatchBaseVersionId())
        .isEqualTo(77L);
  }

  @Test
  void numericReplacementLaunchIsDeniedBeforeChangingPriorRunningSession() {
    GameInstance existing = new GameInstance();
    existing.setTenantId(42L);
    existing.setRuntimeVersion("1.0.0");
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptPinEpoch(1L);
    existing.setScriptPatchPinnedControlPlaneRequestId("pin-request-1");
    existing.setOwnerAccountId(100L);
    existing.setStatus("RUNNING");
    existing = repository.saveAndFlush(existing);
    long existingId = existing.getId();

    assertThatThrownBy(
            () -> service.startSession(new StartSessionRequest(42L, 7L, "cp-2", 100L), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FAILED_PRECONDITION");

    assertThat(repository.findAll()).hasSize(1);
    GameInstance restored = repository.findById(existingId).orElseThrow();
    assertThat(restored.getStatus()).isEqualTo("RUNNING");
    assertThat(restored.getRuntimeVersion()).isEqualTo("1.0.0");
    assertThat(restored.getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(restored.getScriptPinEpoch()).isEqualTo(1L);
    assertThat(restored.getScriptPatchPinnedControlPlaneRequestId()).isEqualTo("pin-request-1");
    assertThat(restored.getOwnerAccountId()).isEqualTo(100L);
    verifyNoInteractions(sessionStateService, worldManagementClient);
  }

  @Test
  void numericReplacementLaunchIsDeniedBeforePriorWorldTermination() {
    GameInstance existing = new GameInstance();
    existing.setTenantId(42L);
    existing.setRuntimeVersion("1.0.0");
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptPinEpoch(1L);
    existing.setScriptPatchPinnedControlPlaneRequestId("pin-request-1");
    existing.setOwnerAccountId(100L);
    existing.setStatus("RUNNING");
    existing = repository.saveAndFlush(existing);
    long existingId = existing.getId();

    assertThatThrownBy(
            () -> service.startSession(new StartSessionRequest(42L, 7L, "cp-2", 100L), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FAILED_PRECONDITION");
    assertThat(repository.findById(existingId)).isPresent();
    assertThat(repository.findById(existingId).orElseThrow().getStatus()).isEqualTo("RUNNING");
    assertThat(repository.findAll()).hasSize(1);
    verifyNoInteractions(sessionStateService, worldManagementClient);
  }

  private static net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse
      deniedNumericLaunchResponse() {
    return net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("FAILED_PRECONDITION")
                .setMessage("Numeric Game Session launch selectors cannot authorize a launch")
                .build())
        .build();
  }

  @TestConfiguration
  static class Config {
    @Bean
    SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
