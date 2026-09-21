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
        new WorldEventDto(null, 1L, 41L, null, "REGION_NOTICE", "notice", null, false, null);
    when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    WorldEventDto result = service.scheduleEvent(request);
    assertNotNull(result.executeAt());
    verify(eventRepository).save(any(WorldEvent.class));
  }

  @Test
  void scheduleWeatherFailsClosedBeforeAnyLookupOrInsert() {
    WorldEventDto request =
        new WorldEventDto(null, 1L, 41L, 7L, "WEATHER_CHANGE", "rainy", null, false, null);

    assertThrows(IllegalStateException.class, () -> service.scheduleEvent(request));

    verifyNoInteractions(regionInstanceRepository, eventRepository);
  }

  @Test
  void scheduleRegionEventRejectsAnotherRuntimeScope() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(7L);
    regionInstance.setTenantId(2L);
    regionInstance.setGameInstanceId(41L);
    when(regionInstanceRepository.findById(7L)).thenReturn(java.util.Optional.of(regionInstance));
    WorldEventDto request =
        new WorldEventDto(null, 1L, 41L, 7L, "REGION_NOTICE", "notice", null, false, null);

    assertThrows(IllegalArgumentException.class, () -> service.scheduleEvent(request));

    verify(eventRepository, never()).save(any());
  }

  @Test
  void scheduleRegionEventRejectsNullEventScope() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(7L);
    regionInstance.setTenantId(1L);
    regionInstance.setGameInstanceId(41L);
    when(regionInstanceRepository.findById(7L)).thenReturn(java.util.Optional.of(regionInstance));
    WorldEventDto request =
        new WorldEventDto(null, null, 41L, 7L, "REGION_NOTICE", "notice", null, false, null);

    assertThrows(IllegalArgumentException.class, () -> service.scheduleEvent(request));

    verify(eventRepository, never()).save(any());
  }

  @Test
  void scheduleRegionEventRejectsNullRegionScope() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(7L);
    regionInstance.setTenantId(null);
    regionInstance.setGameInstanceId(41L);
    when(regionInstanceRepository.findById(7L)).thenReturn(java.util.Optional.of(regionInstance));
    WorldEventDto request =
        new WorldEventDto(null, 1L, 41L, 7L, "REGION_NOTICE", "notice", null, false, null);

    assertThrows(IllegalArgumentException.class, () -> service.scheduleEvent(request));

    verify(eventRepository, never()).save(any());
  }

  @Test
  void processDueEventsLeavesRetainedWeatherUnprocessedAndNonMutating() {
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

    service.processDueEvents();

    assertFalse(event.isProcessed());
    assertNull(regionInstance.getWeather());
    verifyNoInteractions(regionInstanceRepository);
    verify(eventRepository, never()).save(any());
    assertEquals(0, meterRegistry.counter("world_events_processed_total").count());
  }

  @Test
  void processDueEventsStillCompletesNonWeatherEvents() {
    WorldEvent event = new WorldEvent();
    event.setGameInstanceId(41L);
    event.setEventType("REGION_NOTICE");
    event.setExecuteAt(LocalDateTime.now().minusMinutes(1));
    event.setProcessed(false);
    when(eventRepository.findDueEventsForShard(any(), anyInt()))
        .thenReturn(Collections.singletonList(event));

    service.processDueEvents();

    assertTrue(event.isProcessed());
    verify(eventRepository).save(event);
    assertEquals(1, meterRegistry.counter("world_events_processed_total").count());
  }

  @Test
  void processDueEventsLeavesCrossTenantRegionEventUnprocessed() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(7L);
    regionInstance.setTenantId(2L);
    regionInstance.setGameInstanceId(41L);
    WorldEvent event = dueRegionEvent(1L, 41L, regionInstance);

    when(eventRepository.findDueEventsForShard(any(), anyInt()))
        .thenReturn(Collections.singletonList(event));

    service.processDueEvents();

    assertFalse(event.isProcessed());
    verify(eventRepository, never()).save(any());
    assertEquals(0, meterRegistry.counter("world_events_processed_total").count());
  }

  @Test
  void processDueEventsLeavesCrossInstanceRegionEventUnprocessed() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(7L);
    regionInstance.setTenantId(1L);
    regionInstance.setGameInstanceId(42L);
    WorldEvent event = dueRegionEvent(1L, 41L, regionInstance);

    when(eventRepository.findDueEventsForShard(any(), anyInt()))
        .thenReturn(Collections.singletonList(event));

    service.processDueEvents();

    assertFalse(event.isProcessed());
    verify(eventRepository, never()).save(any());
    assertEquals(0, meterRegistry.counter("world_events_processed_total").count());
  }

  @Test
  void processDueEventsCompletesValidRegionEvent() {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(7L);
    regionInstance.setTenantId(1L);
    regionInstance.setGameInstanceId(41L);
    WorldEvent event = dueRegionEvent(1L, 41L, regionInstance);

    when(eventRepository.findDueEventsForShard(any(), anyInt()))
        .thenReturn(Collections.singletonList(event));

    service.processDueEvents();

    assertTrue(event.isProcessed());
    verify(eventRepository).save(event);
    assertEquals(1, meterRegistry.counter("world_events_processed_total").count());
  }

  private WorldEvent dueRegionEvent(
      Long tenantId, Long gameInstanceId, RegionInstance regionInstance) {
    WorldEvent event = new WorldEvent();
    event.setTenantId(tenantId);
    event.setGameInstanceId(gameInstanceId);
    event.setRegionInstance(regionInstance);
    event.setEventType("REGION_NOTICE");
    event.setExecuteAt(LocalDateTime.now().minusMinutes(1));
    event.setProcessed(false);
    return event;
  }
}
