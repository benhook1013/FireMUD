package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
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

  @BeforeEach
  void setup() {
    repository = mock(GameInstanceRepository.class);
    mapper = mock(GameInstanceMapper.class);
    stateService = mock(SessionStateService.class);
    meterRegistry = new SimpleMeterRegistry();
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
            new DevIsolatedProperties(false));
  }

  @Test
  void startSessionSavesState() {
    StartSessionRequest request = new StartSessionRequest(1L, "v1", null, 42L);
    GameInstance entity = new GameInstance();
    entity.setId(10L);
    entity.setTenantId(1L);
    entity.setRuntimeVersion("v1");
    entity.setOwnerAccountId(42L);
    entity.setStatus("RUNNING");

    when(repository.save(org.mockito.ArgumentMatchers.any(GameInstance.class))).thenReturn(entity);
    GameInstanceDto dto = new GameInstanceDto(10L, 1L, "v1", null, 42L, "RUNNING");
    when(mapper.toDto(entity)).thenReturn(dto);

    service.startSession(request);

    verify(stateService).saveState(dto);
  }

  @Test
  void stopSessionDeletesState() {
    GameInstance entity = new GameInstance();
    entity.setId(10L);
    entity.setTenantId(1L);
    entity.setRuntimeVersion("v1");
    entity.setOwnerAccountId(42L);
    entity.setStatus("RUNNING");

    when(repository.findById(10L)).thenReturn(Optional.of(entity));
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(new GameInstanceDto(10L, 1L, "v1", null, 42L, "STOPPED"));

    service.stopSession(10L);

    verify(stateService).deleteState(1L, 10L);
  }
}
