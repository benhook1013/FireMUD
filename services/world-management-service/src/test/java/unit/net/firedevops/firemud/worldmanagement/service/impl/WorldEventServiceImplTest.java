package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Collections;
import net.firedevops.firemud.worldmanagement.dto.WorldEventDto;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import net.firedevops.firemud.worldmanagement.mapper.WorldEventMapper;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class WorldEventServiceImplTest {
  private WorldEventRepository eventRepository;
  private RegionInstanceRepository regionInstanceRepository;
  private WorldEventMapper mapper = Mappers.getMapper(WorldEventMapper.class);
  private WorldEventServiceImpl service;
  private SimpleMeterRegistry meterRegistry;
  private net.firedevops.firemud.worldmanagement.config.WorldProperties worldProperties;

  @BeforeEach
  void setUp() {
    eventRepository = mock(WorldEventRepository.class);
    regionInstanceRepository = mock(RegionInstanceRepository.class);
    meterRegistry = new SimpleMeterRegistry();
    worldProperties = new net.firedevops.firemud.worldmanagement.config.WorldProperties();
    worldProperties.setLocalShardId(0);
    service =
        new WorldEventServiceImpl(
            eventRepository, regionInstanceRepository, mapper, meterRegistry, worldProperties);
    service.initMetrics();
  }

  @Test
  void scheduleEventSetsExecuteAt() {
    WorldEventDto request =
        new WorldEventDto(null, 1L, 41L, null, "WEATHER_CHANGE", "rainy", null, false, null);
    when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    WorldEventDto result = service.scheduleEvent(request);
    assertNotNull(result.executeAt());
    verify(eventRepository).save(any(WorldEvent.class));
  }

  @Test
  void processDueEventsUpdatesWeather() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(1L);
    WorldEvent event = new WorldEvent();
    event.setRegionInstance(regionInstance);
    event.setGameInstanceId(41L);
    event.setEventType("WEATHER_CHANGE");
    event.setEventData("sunny");
    event.setExecuteAt(LocalDateTime.now().minusMinutes(1));
    event.setProcessed(false);

    when(eventRepository.findDueEventsForShard(any(), anyInt()))
        .thenReturn(Collections.singletonList(event));
    when(regionInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.processDueEvents();

    assertTrue(event.isProcessed());
    assertEquals("sunny", regionInstance.getWeather());
    verify(regionInstanceRepository).save(regionInstance);
    verify(eventRepository, times(1)).save(event);
  }
}
