package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import net.firedevops.firemud.dto.WorldEventDto;
import net.firedevops.firemud.entity.Region;
import net.firedevops.firemud.entity.WorldEvent;
import net.firedevops.firemud.mapper.WorldEventMapper;
import net.firedevops.firemud.repository.RegionRepository;
import net.firedevops.firemud.repository.WorldEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class WorldEventServiceImplTest {
  private WorldEventRepository eventRepository;
  private RegionRepository regionRepository;
  private WorldEventMapper mapper = Mappers.getMapper(WorldEventMapper.class);
  private WorldEventServiceImpl service;

  @BeforeEach
  void setUp() {
    eventRepository = mock(WorldEventRepository.class);
    regionRepository = mock(RegionRepository.class);
    service = new WorldEventServiceImpl(eventRepository, regionRepository, mapper);
  }

  @Test
  void scheduleEventSetsExecuteAt() {
    WorldEventDto request =
        new WorldEventDto(null, 1L, null, "WEATHER_CHANGE", "rainy", null, false, null);
    when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    WorldEventDto result = service.scheduleEvent(request);
    assertNotNull(result.executeAt());
    verify(eventRepository).save(any(WorldEvent.class));
  }

  @Test
  void processDueEventsUpdatesWeather() {
    Region region = new Region();
    region.setId(1L);
    WorldEvent event = new WorldEvent();
    event.setRegion(region);
    event.setEventType("WEATHER_CHANGE");
    event.setEventData("sunny");
    event.setExecuteAt(LocalDateTime.now().minusMinutes(1));
    event.setProcessed(false);

    when(eventRepository.findByProcessedFalseAndExecuteAtBefore(any()))
        .thenReturn(Collections.singletonList(event));
    when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.processDueEvents();

    assertTrue(event.isProcessed());
    assertEquals("sunny", region.getWeather());
    verify(regionRepository).save(region);
    verify(eventRepository, times(1)).save(event);
  }
}
