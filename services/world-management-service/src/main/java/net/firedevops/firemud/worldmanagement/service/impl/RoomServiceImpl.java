package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.i18n.LocalizedTextVariants;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto.RoomExitSnapshotDto;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed dependencies are stored internally")
@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {
  private static final Logger LOG = LoggerFactory.getLogger(RoomServiceImpl.class);
  private static final String DEFAULT_SOURCE_LOCALE = "en-NZ";

  private final RoomInstanceRepository roomInstanceRepository;
  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final WorldProperties worldProperties;
  private final RoomInstanceExitRepository roomInstanceExitRepository;
  private final tools.jackson.databind.ObjectMapper objectMapper;

  private static final int SHORT_DESCRIPTION_LENGTH = 120;

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
  public RoomDto getRoom(Long tenantId, Long gameInstanceId, Long roomInstanceRowId) {
    String key = cacheKey(tenantId, gameInstanceId, roomInstanceRowId);
    try {
      Object cached = redisTemplate.opsForValue().get(key);
      if (cached instanceof RoomDto dto) {
        cacheHitCounter.increment();
        return dto;
      }
    } catch (RuntimeException ex) {
      LOG.warn("Room cache read failed for key {}", key, ex);
    }
    cacheMissCounter.increment();
    RoomDto dto =
        roomInstanceRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceRowId(
                tenantId, gameInstanceId, roomInstanceRowId)
            .map(this::toDto)
            .orElseThrow(() -> new IllegalArgumentException("Room not found"));
    try {
      redisTemplate.opsForValue().set(key, dto, Duration.ofSeconds(cacheTtlSeconds));
    } catch (RuntimeException ex) {
      LOG.warn("Room cache write failed for key {}", key, ex);
    }
    return dto;
  }

  @Override
  @Timed(value = "room.snapshot")
  @Transactional(readOnly = true)
  public RoomSnapshotDto getRoomSnapshot(
      Long tenantId, Long gameInstanceId, Long roomInstanceRowId, String preferredLocaleTag) {
    RoomInstance room =
        roomInstanceRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceRowId(
                tenantId, gameInstanceId, roomInstanceRowId)
            .orElseThrow(() -> new IllegalArgumentException("Room not found"));
    long fromRoomInstanceRecordId = room.getId();
    List<RoomExitSnapshotDto> exits =
        roomInstanceExitRepository
            .findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(
                tenantId, gameInstanceId, fromRoomInstanceRecordId)
            .stream()
            .map(exit -> toExitSnapshot(exit, preferredLocaleTag))
            .collect(Collectors.toList());
    String roomName =
        resolveLocalizedText(
            room.getName(), room.getNameLocalizedVariantsJson(), preferredLocaleTag);
    String longDescription =
        resolveLocalizedText(
            room.getDescription(), room.getDescriptionLocalizedVariantsJson(), preferredLocaleTag);
    return new RoomSnapshotDto(
        room.getRoomInstanceRowId(),
        room.getTenantId(),
        gameInstanceId,
        roomName,
        buildShortDescription(longDescription),
        longDescription,
        exits,
        Map.of("lighting", "dim"),
        List.of());
  }

  private RoomExitSnapshotDto toExitSnapshot(RoomInstanceExit exit, String preferredLocaleTag) {
    String targetName =
        resolveLocalizedText(
            exit.getToRoomInstance().getName(),
            exit.getToRoomInstance().getNameLocalizedVariantsJson(),
            preferredLocaleTag);
    String description = "Leads toward " + targetName;
    return new RoomExitSnapshotDto(
        exit.getId(),
        exit.getToRoomInstance().getRoomInstanceRowId(),
        targetName,
        exit.getDirection(),
        exit.getDirection(),
        description,
        exit.getCost());
  }

  private String buildShortDescription(String description) {
    if (description == null || description.isBlank()) {
      return "";
    }
    if (description.length() <= SHORT_DESCRIPTION_LENGTH) {
      return description;
    }
    return description.substring(0, SHORT_DESCRIPTION_LENGTH) + "...";
  }

  private String cacheKey(Long tenantId, Long gameInstanceId, Long roomInstanceRowId) {
    return "room:" + tenantId + ":" + gameInstanceId + ":" + roomInstanceRowId;
  }

  private RoomDto toDto(RoomInstance room) {
    return new RoomDto(
        room.getRoomInstanceRowId(),
        room.getTenantId(),
        room.getZoneInstance().getRegionInstance().getId(),
        room.getName(),
        room.getDescription());
  }

  private String resolveLocalizedText(
      String sourceText, String localizedVariantsJson, String preferredLocaleTag) {
    if (localizedVariantsJson == null || localizedVariantsJson.isBlank()) {
      return sourceText == null ? "" : sourceText;
    }
    try {
      LocalizedTextVariants parsed =
          objectMapper.readValue(localizedVariantsJson, LocalizedTextVariants.class);
      String effectiveSourceText =
          sourceText != null && !sourceText.isBlank() ? sourceText : parsed.sourceText();
      if (effectiveSourceText == null || effectiveSourceText.isBlank()) {
        return "";
      }
      LocalizedTextVariants variants =
          new LocalizedTextVariants(
              parsed.sourceLocaleTag() == null || parsed.sourceLocaleTag().isBlank()
                  ? DEFAULT_SOURCE_LOCALE
                  : parsed.sourceLocaleTag(),
              effectiveSourceText,
              parsed.localizedVariants());
      return variants.resolve(preferredLocaleTag).text();
    } catch (Exception ex) {
      LOG.warn("Failed to parse localized room text variants; falling back to source text", ex);
      return sourceText == null ? "" : sourceText;
    }
  }
}
