package net.firedevops.firemud.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.config.WorldProperties;
import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.mapper.RoomMapper;
import net.firedevops.firemud.repository.RoomRepository;
import net.firedevops.firemud.service.RoomService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed dependencies are stored internally")
@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {
  private final RoomRepository roomRepository;
  private final RoomMapper roomMapper;
  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final WorldProperties worldProperties;

  private Counter cacheHitCounter;
  private Counter cacheMissCounter;

  private long cacheTtlSeconds;

  // Setter used in tests to override TTL
  void setCacheTtlSeconds(long seconds) {
    this.cacheTtlSeconds = seconds;
  }

  @PostConstruct
  void initMetrics() {
    cacheHitCounter = meterRegistry.counter("room_cache_hits_total");
    cacheMissCounter = meterRegistry.counter("room_cache_misses_total");
    cacheTtlSeconds = worldProperties.getRoom().getCacheTtlSeconds();
  }

  @Override
  @Timed(value = "room.get")
  public RoomDto getRoom(Long tenantId, Long roomId) {
    String key = cacheKey(tenantId, roomId);
    Object cached = redisTemplate.opsForValue().get(key);
    if (cached instanceof RoomDto dto) {
      cacheHitCounter.increment();
      return dto;
    }
    cacheMissCounter.increment();
    RoomDto dto =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getTenantId().equals(tenantId))
            .map(roomMapper::toDto)
            .orElseThrow(() -> new IllegalArgumentException("Room not found"));
    redisTemplate.opsForValue().set(key, dto, Duration.ofSeconds(cacheTtlSeconds));
    return dto;
  }

  private String cacheKey(Long tenantId, Long roomId) {
    return "room:" + tenantId + ":" + roomId;
  }
}
