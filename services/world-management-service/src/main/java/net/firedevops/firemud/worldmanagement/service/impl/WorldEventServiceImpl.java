package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.WorldEventDto;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import net.firedevops.firemud.worldmanagement.mapper.WorldEventMapper;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.service.WorldEventService;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and metrics remain internal")
public class WorldEventServiceImpl implements WorldEventService {
  private final WorldEventRepository eventRepository;
  private final RegionInstanceRepository regionInstanceRepository;
  private final WorldEventMapper mapper;
  private final MeterRegistry meterRegistry;
  private final WorldProperties worldProperties;
  private Counter eventsProcessedCounter;
  private Counter weatherDeferredCounter;
  private Counter regionScopeMismatchCounter;
  private static final Logger logger = LoggingUtil.getLogger(WorldEventServiceImpl.class);

  @PostConstruct
  void initMetrics() {
    this.eventsProcessedCounter = meterRegistry.counter("world_events_processed_total");
    this.weatherDeferredCounter =
        meterRegistry.counter("world_events_skipped_total", "reason", "weather_deferred");
    this.regionScopeMismatchCounter =
        meterRegistry.counter("world_events_skipped_total", "reason", "region_scope_mismatch");
  }

  @Override
  @Timed(value = "worldEvent.schedule")
  public WorldEventDto scheduleEvent(WorldEventDto dto) {
    if (WorldEvent.WEATHER_CHANGE_EVENT_TYPE.equals(dto.eventType())) {
      throw new IllegalArgumentException(
          "WEATHER_CHANGE_UNAVAILABLE: weather aggregate and effect fence are not established");
    }
    WorldEvent entity = mapper.toEntity(dto);
    if (entity.getExecuteAt() == null) {
      entity.setExecuteAt(LocalDateTime.now());
    }
    if (dto.regionId() != null) {
      RegionInstance regionInstance =
          regionInstanceRepository
              .findById(dto.regionId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "REGION_INSTANCE_NOT_FOUND: runtime region instance not found"));
      if (dto.tenantId() == null
          || dto.gameInstanceId() == null
          || regionInstance.getTenantId() == null
          || regionInstance.getGameInstanceId() == null
          || !Objects.equals(dto.tenantId(), regionInstance.getTenantId())
          || !Objects.equals(dto.gameInstanceId(), regionInstance.getGameInstanceId())) {
        throw new IllegalArgumentException(
            "REGION_INSTANCE_SCOPE_MISMATCH: runtime region is outside the event scope");
      }
      entity.setRegionInstance(regionInstance);
    }
    eventRepository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Scheduled(fixedDelayString = "${world.event.check-delay-ms:60000}")
  @Transactional
  @Timed(value = "worldEvent.processDue")
  public void processDueEvents() {
    LocalDateTime now = LocalDateTime.now();
    List<WorldEvent> events =
        eventRepository.findDueEventsForShard(now, worldProperties.getLocalShardId());
    int processedCount = 0;
    for (WorldEvent event : events) {
      if (WorldEvent.WEATHER_CHANGE_EVENT_TYPE.equals(event.getEventType())) {
        // Retained weather events cannot become an admitted mutation while the selector is open.
        weatherDeferredCounter.increment();
        continue;
      }
      if (!hasMatchingRegionScope(event)) {
        // A retained or bypass-inserted row must not mutate or be acknowledged outside its scope.
        regionScopeMismatchCounter.increment();
        continue;
      }
      event.setProcessed(true);
      event.setProcessedAt(now);
      eventRepository.save(event);
      eventsProcessedCounter.increment();
      processedCount++;
    }
    if (processedCount > 0) {
      logger.debug("Processed {} world events", processedCount);
    }
  }

  private boolean hasMatchingRegionScope(WorldEvent event) {
    RegionInstance region = event.getRegionInstance();
    return region == null
        || (event.getTenantId() != null
            && event.getTenantId().equals(region.getTenantId())
            && event.getGameInstanceId() != null
            && event.getGameInstanceId().equals(region.getGameInstanceId()));
  }
}
