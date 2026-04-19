package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.i18n.LocalizedTextVariants;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class RoomServiceImplTest {
  private RoomInstanceRepository repository;
  private RedisTemplate<String, Object> redisTemplate;
  private ValueOperations<String, Object> valueOps;
  private RoomServiceImpl service;
  private WorldProperties props;
  private RoomInstanceExitRepository exitRepository;

  @BeforeEach
  void setup() {
    repository = mock(RoomInstanceRepository.class);
    redisTemplate = mockRedisTemplate();
    valueOps = mockValueOperations();
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    props = new WorldProperties();
    props.getRoom().setCacheTtlSeconds(1);
    exitRepository = mock(RoomInstanceExitRepository.class);
    when(exitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceId(
            anyLong(), anyLong(), anyLong()))
        .thenReturn(Collections.emptyList());
    service =
        new RoomServiceImpl(
            repository,
            redisTemplate,
            new SimpleMeterRegistry(),
            props,
            exitRepository,
            new ObjectMapper());
    service.initMetrics();
  }

  @Test
  void getRoomCachesResult() {
    RoomInstance entity = roomInstance(1L, 41L, 1L, 2L, "A");
    when(repository.findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1L))
        .thenReturn(Optional.of(entity));

    RoomDto first = service.getRoom(1L, 41L, 1L);

    assertEquals("A", first.name());
    verify(valueOps).set("room:1:41:1", first, java.time.Duration.ofSeconds(1));

    when(valueOps.get("room:1:41:1")).thenReturn(first);
    RoomDto second = service.getRoom(1L, 41L, 1L);
    assertEquals("A", second.name());
    verify(repository, times(1)).findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1L);
  }

  @Test
  void getRoomSnapshotIncludesExitsAndDescription() {
    RoomInstance room = roomInstance(1L, 41L, 1021L, 200L, "Candle-lit Antechamber");
    String longDesc =
        "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth "
            + "from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.";
    room.setDescription(longDesc);
    when(repository.findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1021L))
        .thenReturn(Optional.of(room));

    RoomInstance other = roomInstance(1L, 41L, 2045L, 200L, "Crafting Hall of Ember");

    RoomInstanceExit exit = new RoomInstanceExit();
    exit.setId(5001L);
    exit.setTenantId(1L);
    exit.setGameInstanceId(41L);
    exit.setFromRoomInstance(room);
    exit.setToRoomInstance(other);
    exit.setDirection("NORTH");
    exit.setCost(1);
    when(exitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceId(1L, 41L, 1021L))
        .thenReturn(List.of(exit));

    RoomSnapshotDto snapshot = service.getRoomSnapshot(1L, 41L, 1021L, "");
    assertEquals("Candle-lit Antechamber", snapshot.roomName());
    assertEquals(longDesc, snapshot.longDescription());
    assertEquals(1, snapshot.exits().size());
    assertEquals(2045L, snapshot.exits().get(0).targetRoomId().longValue());
    assertEquals("NORTH", snapshot.exits().get(0).direction());
  }

  @Test
  void getRoomSnapshotResolvesLocalizedRoomTextWhenVariantsExist() throws Exception {
    RoomInstance room = roomInstance(1L, 41L, 1021L, 200L, "Candle-lit Antechamber");
    room.setDescription("Stalactites drip along the northern wall.");
    room.setNameLocalizedVariantsJson(
        new ObjectMapper()
            .writeValueAsString(
                LocalizedTextVariants.source("en-NZ", "Candle-lit Antechamber")
                    .withVariant("fr", "Antichambre eclairee par les chandelles")));
    room.setDescriptionLocalizedVariantsJson(
        new ObjectMapper()
            .writeValueAsString(
                LocalizedTextVariants.source("en-NZ", "Stalactites drip along the northern wall.")
                    .withVariant("fr", "Des stalactites perlent le long du mur nord.")));
    when(repository.findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1021L))
        .thenReturn(Optional.of(room));

    RoomInstance targetRoom = roomInstance(1L, 41L, 2045L, 200L, "North Hall");
    targetRoom.setNameLocalizedVariantsJson(
        new ObjectMapper()
            .writeValueAsString(
                LocalizedTextVariants.source("en-NZ", "North Hall")
                    .withVariant("fr", "Salle du Nord")));

    RoomInstanceExit exit = new RoomInstanceExit();
    exit.setId(5001L);
    exit.setTenantId(1L);
    exit.setGameInstanceId(41L);
    exit.setFromRoomInstance(room);
    exit.setToRoomInstance(targetRoom);
    exit.setDirection("NORTH");
    exit.setCost(1);
    when(exitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceId(1L, 41L, 1021L))
        .thenReturn(List.of(exit));

    RoomSnapshotDto snapshot = service.getRoomSnapshot(1L, 41L, 1021L, "fr");

    assertEquals("Antichambre eclairee par les chandelles", snapshot.roomName());
    assertEquals("Des stalactites perlent le long du mur nord.", snapshot.longDescription());
    assertEquals("Des stalactites perlent le long du mur nord.", snapshot.shortDescription());
    assertEquals("Salle du Nord", snapshot.exits().get(0).targetRoomName());
    assertEquals("NORTH", snapshot.exits().get(0).label());
    assertEquals("Leads toward Salle du Nord", snapshot.exits().get(0).description());
  }

  @Test
  void getRoomIgnoresCacheReadFailure() {
    RoomInstance entity = roomInstance(1L, 41L, 1L, 2L, "A");
    when(valueOps.get("room:1:41:1"))
        .thenThrow(new RedisSystemException("boom", new RuntimeException()));
    when(repository.findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1L))
        .thenReturn(Optional.of(entity));

    RoomDto dto = service.getRoom(1L, 41L, 1L);

    assertEquals("A", dto.name());
    verify(repository).findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1L);
  }

  @Test
  void getRoomIgnoresCacheWriteFailure() {
    RoomInstance entity = roomInstance(1L, 41L, 1L, 2L, "A");
    when(repository.findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1L))
        .thenReturn(Optional.of(entity));
    doThrow(new RedisSystemException("boom", new RuntimeException()))
        .when(valueOps)
        .set(eq("room:1:41:1"), any(), eq(java.time.Duration.ofSeconds(1)));

    RoomDto dto = service.getRoom(1L, 41L, 1L);

    assertEquals("A", dto.name());
    verify(repository).findByTenantIdAndGameInstanceIdAndRoomInstanceId(1L, 41L, 1L);
  }

  private static RoomInstance roomInstance(
      long tenantId, long gameInstanceId, long roomInstanceId, long regionInstanceId, String name) {
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(regionInstanceId);
    ZoneInstance zoneInstance = new ZoneInstance();
    zoneInstance.setRegionInstance(regionInstance);
    RoomInstance room = new RoomInstance();
    room.setTenantId(tenantId);
    room.setGameInstanceId(gameInstanceId);
    room.setRoomInstanceId(roomInstanceId);
    room.setZoneInstance(zoneInstance);
    room.setName(name);
    return room;
  }

  @SuppressWarnings("unchecked")
  private static RedisTemplate<String, Object> mockRedisTemplate() {
    return mock(RedisTemplate.class);
  }

  @SuppressWarnings("unchecked")
  private static ValueOperations<String, Object> mockValueOperations() {
    return mock(ValueOperations.class);
  }
}
