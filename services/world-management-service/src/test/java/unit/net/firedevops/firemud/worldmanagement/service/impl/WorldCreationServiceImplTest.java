package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldCreationServiceImplTest {
  private RegionRepository regionRepository;
  private WorldCreationServiceImpl service;
  private MeterRegistry meterRegistry;
  private net.firedevops.firemud.worldmanagement.config.WorldProperties worldProperties;
  private net.firedevops.firemud.worldmanagement.client.GameDesignClient gameDesignClient;
  private net.firedevops.firemud.common.saga.SagaRunner sagaRunner;

  @BeforeEach
  void setup() throws Exception {
    regionRepository = mock(RegionRepository.class);
    meterRegistry = mock(MeterRegistry.class);
    when(meterRegistry.counter(anyString())).thenReturn(mock(Counter.class));
    worldProperties = new net.firedevops.firemud.worldmanagement.config.WorldProperties();
    worldProperties.setLocalShardId(0);
    gameDesignClient = mock(net.firedevops.firemud.worldmanagement.client.GameDesignClient.class);
    sagaRunner = mock(net.firedevops.firemud.common.saga.SagaRunner.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              try {
                ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              } catch (net.firedevops.firemud.common.saga.SagaException e) {
                throw new RuntimeException(e);
              }
              return null;
            })
        .when(sagaRunner)
        .run(org.mockito.ArgumentMatchers.any());
    service =
        new WorldCreationServiceImpl(
            regionRepository, meterRegistry, worldProperties, gameDesignClient, sagaRunner);
  }

  @Test
  void createWorldRunsSaga() {
    when(regionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    assertDoesNotThrow(() -> service.createWorld(1L, 1L));
    verify(regionRepository).save(argThat(r -> r.getShardId() == 0));
  }
}
