package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.config.WorldProperties;
import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.entity.Region;
import net.firedevops.firemud.entity.Room;
import net.firedevops.firemud.mapper.RoomMapper;
import net.firedevops.firemud.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RoomServiceImplTest {
  private RoomRepository repository;
  private RedisTemplate<String, Object> redisTemplate;
  private ValueOperations<String, Object> valueOps;
  private RoomMapper mapper = Mappers.getMapper(RoomMapper.class);
  private RoomServiceImpl service;
  private WorldProperties props;

  @BeforeEach
  void setup() {
    repository = mock(RoomRepository.class);
    redisTemplate = mock(RedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    props = new WorldProperties();
    props.getRoom().setCacheTtlSeconds(1);
    service =
        new RoomServiceImpl(repository, mapper, redisTemplate, new SimpleMeterRegistry(), props);
    service.initMetrics();
  }

  @Test
  void getRoomCachesResult() {
    Room entity = new Room();
    entity.setId(1L);
    entity.setTenantId(1L);
    Region region = new Region();
    region.setId(2L);
    entity.setRegion(region);
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
}
