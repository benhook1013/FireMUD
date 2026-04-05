package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.i18n.LocalizedTextVariants;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.mapper.RoomMapper;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class RoomServiceImplTest {
  private RoomRepository repository;
  private RedisTemplate<String, Object> redisTemplate;
  private ValueOperations<String, Object> valueOps;
  private RoomMapper mapper = Mappers.getMapper(RoomMapper.class);
  private RoomServiceImpl service;
  private WorldProperties props;
  private RoomExitRepository exitRepository;

  @BeforeEach
  void setup() {
    repository = mock(RoomRepository.class);
    redisTemplate = mockRedisTemplate();
    valueOps = mockValueOperations();
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    props = new WorldProperties();
    props.getRoom().setCacheTtlSeconds(1);
    exitRepository = mock(RoomExitRepository.class);
    when(exitRepository.findByTenantIdAndFromRoomId(anyLong(), anyLong()))
        .thenReturn(Collections.emptyList());
    service =
        new RoomServiceImpl(
            repository,
            mapper,
            redisTemplate,
            new SimpleMeterRegistry(),
            props,
            exitRepository,
            new ObjectMapper());
    service.initMetrics();
  }

  @Test
  void getRoomCachesResult() {
    Room entity = new Room();
    entity.setId(1L);
    entity.setTenantId(1L);
    Region region = new Region();
    region.setId(2L);
    Zone zone = zoneWithRegion(region);
    entity.setZone(zone);
    entity.setName("A");
    when(repository.findById(1L)).thenReturn(Optional.of(entity));
    RoomDto first = service.getRoom(1L, 1L);
    assertEquals("A", first.name());
    verify(valueOps).set("room:1:1", first, java.time.Duration.ofSeconds(1));

    when(valueOps.get("room:1:1")).thenReturn(first);
    RoomDto second = service.getRoom(1L, 1L);
    assertEquals("A", second.name());
    verify(repository, times(1)).findById(1L);
  }

  @Test
  void getRoomSnapshotIncludesExitsAndDescription() {
    Room room = new Room();
    room.setId(1021L);
    room.setTenantId(1L);
    Region region = new Region();
    region.setId(200L);
    Zone zone = zoneWithRegion(region);
    room.setZone(zone);
    room.setName("Candle-lit Antechamber");
    String longDesc =
        "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth "
            + "from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.";
    room.setDescription(longDesc);
    when(repository.findById(1021L)).thenReturn(Optional.of(room));

    Room other = new Room();
    other.setId(2045L);
    other.setTenantId(1L);
    other.setZone(zone);
    other.setName("Crafting Hall of Ember");

    RoomExit exit = new RoomExit();
    exit.setId(5001L);
    exit.setTenantId(1L);
    exit.setFromRoom(room);
    exit.setToRoom(other);
    exit.setDirection("NORTH");
    exit.setCost(1);
    when(exitRepository.findByTenantIdAndFromRoomId(1L, 1021L)).thenReturn(List.of(exit));

    RoomSnapshotDto snapshot = service.getRoomSnapshot(1L, 1021L, "");
    assertEquals("Candle-lit Antechamber", snapshot.roomName());
    assertEquals(longDesc, snapshot.longDescription());
    assertEquals(1, snapshot.exits().size());
    assertEquals(2045L, snapshot.exits().get(0).targetRoomId().longValue());
    assertEquals("NORTH", snapshot.exits().get(0).direction());
  }

  @Test
  void getRoomSnapshotResolvesLocalizedRoomTextWhenVariantsExist() throws Exception {
    Room room = new Room();
    room.setId(1021L);
    room.setTenantId(1L);
    Region region = new Region();
    region.setId(200L);
    Zone zone = zoneWithRegion(region);
    room.setZone(zone);
    room.setName("Candle-lit Antechamber");
    room.setDescription("Stalactites drip along the northern wall.");
    room.setNameLocalizedVariantsJson(
        new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(
                LocalizedTextVariants.source("en-NZ", "Candle-lit Antechamber")
                    .withVariant("fr", "Antichambre eclairee par les chandelles")));
    room.setDescriptionLocalizedVariantsJson(
        new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(
                LocalizedTextVariants.source("en-NZ", "Stalactites drip along the northern wall.")
                    .withVariant("fr", "Des stalactites perlent le long du mur nord.")));
    when(repository.findById(1021L)).thenReturn(Optional.of(room));

    Room targetRoom = new Room();
    targetRoom.setId(2045L);
    targetRoom.setTenantId(1L);
    targetRoom.setZone(zone);
    targetRoom.setName("North Hall");
    targetRoom.setNameLocalizedVariantsJson(
        new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(
                LocalizedTextVariants.source("en-NZ", "North Hall")
                    .withVariant("fr", "Salle du Nord")));

    RoomExit exit = new RoomExit();
    exit.setId(5001L);
    exit.setTenantId(1L);
    exit.setFromRoom(room);
    exit.setToRoom(targetRoom);
    exit.setDirection("NORTH");
    exit.setCost(1);
    when(exitRepository.findByTenantIdAndFromRoomId(1L, 1021L)).thenReturn(List.of(exit));

    RoomSnapshotDto snapshot = service.getRoomSnapshot(1L, 1021L, "fr");

    assertEquals("Antichambre eclairee par les chandelles", snapshot.roomName());
    assertEquals("Des stalactites perlent le long du mur nord.", snapshot.longDescription());
    assertEquals("Des stalactites perlent le long du mur nord.", snapshot.shortDescription());
    assertEquals("Salle du Nord", snapshot.exits().get(0).targetRoomName());
    assertEquals("NORTH", snapshot.exits().get(0).label());
    assertEquals("Leads toward Salle du Nord", snapshot.exits().get(0).description());
  }

  @Test
  void getRoomIgnoresCacheReadFailure() {
    Room entity = new Room();
    entity.setId(1L);
    entity.setTenantId(1L);
    Region region = new Region();
    region.setId(2L);
    entity.setZone(zoneWithRegion(region));
    entity.setName("A");
    when(valueOps.get("room:1:1"))
        .thenThrow(new RedisSystemException("boom", new RuntimeException()));
    when(repository.findById(1L)).thenReturn(Optional.of(entity));

    RoomDto dto = service.getRoom(1L, 1L);

    assertEquals("A", dto.name());
    verify(repository).findById(1L);
  }

  @Test
  void getRoomIgnoresCacheWriteFailure() {
    Room entity = new Room();
    entity.setId(1L);
    entity.setTenantId(1L);
    Region region = new Region();
    region.setId(2L);
    entity.setZone(zoneWithRegion(region));
    entity.setName("A");
    when(repository.findById(1L)).thenReturn(Optional.of(entity));
    doThrow(new RedisSystemException("boom", new RuntimeException()))
        .when(valueOps)
        .set(eq("room:1:1"), any(), eq(java.time.Duration.ofSeconds(1)));

    RoomDto dto = service.getRoom(1L, 1L);

    assertEquals("A", dto.name());
    verify(repository).findById(1L);
  }

  @SuppressWarnings("unchecked")
  private static RedisTemplate<String, Object> mockRedisTemplate() {
    return mock(RedisTemplate.class);
  }

  @SuppressWarnings("unchecked")
  private static ValueOperations<String, Object> mockValueOperations() {
    return mock(ValueOperations.class);
  }

  private static Zone zoneWithRegion(Region region) {
    Zone zone = new Zone();
    zone.setRegion(region);
    return zone;
  }
}
