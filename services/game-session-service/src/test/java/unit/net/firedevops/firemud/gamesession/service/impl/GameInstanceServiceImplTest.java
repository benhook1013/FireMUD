package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameInstanceServiceImplTest {
  private GameInstanceRepository repository;
  private GameInstanceMapper mapper;
  private SessionStateService stateService;
  private SimpleMeterRegistry meterRegistry;
  private GameInstanceServiceImpl service;
  private Map<Long, GameInstance> store;
  private AtomicLong nextId;

  @BeforeEach
  void setup() {
    repository = mock(GameInstanceRepository.class);
    mapper = mock(GameInstanceMapper.class);
    stateService = mock(SessionStateService.class);
    meterRegistry = new SimpleMeterRegistry();
    store = new HashMap<>();
    nextId = new AtomicLong(10L);
    service =
        new GameInstanceServiceImpl(
            repository,
            mapper,
            stateService,
            null,
            null,
            null,
            null,
            meterRegistry,
            new DevIsolatedProperties(false),
            immediateTransactionOperations());
    configureRepositoryPersistence();
    configureMapper();
  }

  @Test
  void startSessionSavesState() {
    StartSessionRequest request = new StartSessionRequest(1L, "v1", null, 42L);

    GameInstanceDto dto = service.startSession(request);

    assertEquals("RUNNING", dto.status());
    verify(repository, never()).findFirstByTenantIdAndOwnerAccountIdAndStatus(1L, 42L, "RUNNING");
    verify(stateService).saveState(dto);
  }

  @Test
  void startSessionStopsExistingRunningSessionOnlyWithinTenantAndOwner() {
    StartSessionRequest request = new StartSessionRequest(2L, "v1", null, 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));

    GameInstanceDto dto = service.startSession(request, true);

    verify(repository).findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING");
    verify(stateService).saveState(dto);
    verify(stateService).deleteState(2L, 7L);
    assertEquals("STOPPED", store.get(7L).getStatus());
    assertEquals("RUNNING", store.get(dto.id()).getStatus());
  }

  @Test
  void startSessionWithoutReplacementLeavesExistingSessionRunning() {
    StartSessionRequest request = new StartSessionRequest(2L, "v1", null, 42L);

    GameInstanceDto dto = service.startSession(request, false);

    verify(repository, never()).findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING");
    verify(stateService).saveState(dto);
  }

  @Test
  void stopSessionDeletesState() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");

    GameInstanceDto dto = service.stopSession(10L);

    verify(stateService).deleteState(1L, 10L);
    assertEquals("STOPPED", dto.status());
    assertEquals("STOPPED", store.get(10L).getStatus());
  }

  @Test
  void startSessionFailsFastWhenStateSaveFails() {
    StartSessionRequest request = new StartSessionRequest(1L, "v1", null, 42L);
    doThrow(new IllegalStateException("redis down")).when(stateService).saveState(any());

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(stateService).saveState(any());
    verify(stateService, never()).deleteState(1L, 10L);
    assertEquals(0, store.size());
  }

  @Test
  void startSessionWithReplacementRestoresExistingRunningStateWhenNewStateSaveFails() {
    StartSessionRequest request = new StartSessionRequest(2L, "v1", null, 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    doThrow(new IllegalStateException("redis down")).when(stateService).saveState(any());

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(7L).getStatus());
    verify(stateService, never()).deleteState(2L, 7L);
  }

  @Test
  void stopSessionFailsFastWhenStateDeleteFails() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
    doThrow(new IllegalStateException("redis down")).when(stateService).deleteState(1L, 10L);

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    verify(stateService).deleteState(1L, 10L);
    verify(stateService, never()).saveState(any(GameInstanceDto.class));
    assertEquals("RUNNING", store.get(10L).getStatus());
  }

  private void configureRepositoryPersistence() {
    when(repository.save(any(GameInstance.class)))
        .thenAnswer(
            invocation -> {
              GameInstance input = invocation.getArgument(0);
              if (input.getId() == null) {
                input.setId(nextId.getAndIncrement());
              }
              store.put(input.getId(), copyOf(input));
              return input;
            });
    when(repository.findById(any(Long.class)))
        .thenAnswer(
            invocation -> {
              Long id = invocation.getArgument(0);
              GameInstance stored = store.get(id);
              return stored == null ? Optional.empty() : Optional.of(copyOf(stored));
            });
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            any(Long.class), any(Long.class), any()))
        .thenAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              Long ownerAccountId = invocation.getArgument(1);
              String status = invocation.getArgument(2);
              return store.values().stream()
                  .filter(instance -> tenantId.equals(instance.getTenantId()))
                  .filter(instance -> ownerAccountId.equals(instance.getOwnerAccountId()))
                  .filter(instance -> status.equals(instance.getStatus()))
                  .findFirst()
                  .map(GameInstanceServiceImplTest::copyOf);
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Long id = invocation.getArgument(0);
              store.remove(id);
              return null;
            })
        .when(repository)
        .deleteById(any(Long.class));
  }

  private void configureMapper() {
    when(mapper.toDto(any(GameInstance.class)))
        .thenAnswer(
            invocation -> {
              GameInstance entity = invocation.getArgument(0);
              return new GameInstanceDto(
                  entity.getId(),
                  entity.getTenantId(),
                  entity.getRuntimeVersion(),
                  entity.getScriptPatchVersion(),
                  entity.getOwnerAccountId(),
                  entity.getStatus());
            });
  }

  private GameInstance persistExisting(
      Long id,
      Long tenantId,
      String runtimeVersion,
      String scriptPatchVersion,
      Long ownerAccountId,
      String status) {
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion(runtimeVersion);
    instance.setScriptPatchVersion(scriptPatchVersion);
    instance.setOwnerAccountId(ownerAccountId);
    instance.setStatus(status);
    store.put(id, copyOf(instance));
    return copyOf(instance);
  }

  private static GameInstance copyOf(GameInstance instance) {
    GameInstance copy = new GameInstance();
    copy.setId(instance.getId());
    copy.setTenantId(instance.getTenantId());
    copy.setRuntimeVersion(instance.getRuntimeVersion());
    copy.setScriptPatchVersion(instance.getScriptPatchVersion());
    copy.setOwnerAccountId(instance.getOwnerAccountId());
    copy.setStatus(instance.getStatus());
    return copy;
  }

  private static org.springframework.transaction.support.TransactionOperations
      immediateTransactionOperations() {
    return new org.springframework.transaction.support.TransactionOperations() {
      @Override
      public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
        return action.doInTransaction(
            new org.springframework.transaction.support.SimpleTransactionStatus());
      }
    };
  }
}
