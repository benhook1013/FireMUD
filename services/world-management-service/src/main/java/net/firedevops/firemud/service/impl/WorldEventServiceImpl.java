package net.firedevops.firemud.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.WorldEventDto;
import net.firedevops.firemud.entity.Region;
import net.firedevops.firemud.entity.WorldEvent;
import net.firedevops.firemud.mapper.WorldEventMapper;
import net.firedevops.firemud.repository.RegionRepository;
import net.firedevops.firemud.repository.WorldEventRepository;
import net.firedevops.firemud.service.WorldEventService;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorldEventServiceImpl implements WorldEventService {
  private final WorldEventRepository eventRepository;
  private final RegionRepository regionRepository;
  private final WorldEventMapper mapper;
  private static final Logger logger = LoggingUtil.getLogger(WorldEventServiceImpl.class);

  @Override
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
  public void processDueEvents() {
    LocalDateTime now = LocalDateTime.now();
    List<WorldEvent> events = eventRepository.findByProcessedFalseAndExecuteAtBefore(now);
    for (WorldEvent event : events) {
      handleEvent(event);
      event.setProcessed(true);
      event.setProcessedAt(now);
      eventRepository.save(event);
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
