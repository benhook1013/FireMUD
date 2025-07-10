package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import net.firedevops.firemud.entity.Region;
import net.firedevops.firemud.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldCreationServiceImplTest {
  private RegionRepository regionRepository;
  private WorldCreationServiceImpl service;

  @BeforeEach
  void setup() {
    regionRepository = mock(RegionRepository.class);
    service = new WorldCreationServiceImpl(regionRepository);
  }

  @Test
  void createWorldRunsSaga() {
    when(regionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    assertDoesNotThrow(() -> service.createWorld(1L, 1L));
    verify(regionRepository).save(any(Region.class));
  }
}
