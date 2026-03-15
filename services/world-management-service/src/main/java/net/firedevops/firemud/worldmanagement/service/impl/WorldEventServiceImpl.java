package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.WorldEventDto;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import net.firedevops.firemud.worldmanagement.mapper.WorldEventMapper;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
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
  private final RegionRepository regionRepository;
  private final WorldEventMapper mapper;
  private final MeterRegistry meterRegistry;
  private final WorldProperties worldProperties;
  private Counter eventsProcessedCounter;
  private static final Logger logger = LoggingUtil.getLogger(WorldEventServiceImpl.class);

  @PostConstruct
  void initMetrics() {
    this.eventsProcessedCounter = meterRegistry.counter("world_events_processed_total");
  }

  @Override
  @Timed(value = "worldEvent.schedule")
  public WorldEventDto scheduleEvent(WorldEventDto dto) {
    WorldEvent entity = mapper.toEntity(dto);
    if (entity.getExecuteAt() == null) {
      entity.setExecuteAt(LocalDateTime.now());
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
    for (WorldEvent event : events) {
      handleEvent(event);
      event.setProcessed(true);
      event.setProcessedAt(now);
      eventRepository.save(event);
      eventsProcessedCounter.increment();
    }
    if (!events.isEmpty()) {
      logger.debug("Processed {} world events", events.size());
    }
  }

  private void handleEvent(WorldEvent event) {
    if ("WEATHER_CHANGE".equals(event.getEventType()) && event.getRegion() != null) {
      Region region = event.getRegion();
      region.setWeather(event.getEventData());
      regionRepository.save(region);
    }
  }
}
