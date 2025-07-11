package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldCreationServiceImplTest {
  private RegionRepository regionRepository;
  private WorldCreationServiceImpl service;
  private MeterRegistry meterRegistry;
  private net.firedevops.firemud.config.WorldProperties worldProperties;

  @BeforeEach
  void setup() {
    regionRepository = mock(RegionRepository.class);
    meterRegistry = mock(MeterRegistry.class);
    when(meterRegistry.counter(anyString())).thenReturn(mock(Counter.class));
    worldProperties = new net.firedevops.firemud.config.WorldProperties();
    worldProperties.setLocalShardId(0);
    service = new WorldCreationServiceImpl(regionRepository, meterRegistry, worldProperties);
  }

  @Test
  void createWorldRunsSaga() {
    when(regionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    assertDoesNotThrow(() -> service.createWorld(1L, 1L));
    verify(regionRepository).save(argThat(r -> r.getShardId() == 0));
  }
}
