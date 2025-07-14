package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.firedevops.firemud.entity.Room;
import net.firedevops.firemud.entity.RoomExit;
import net.firedevops.firemud.repository.RoomExitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TravelServiceImplTest {
  private RoomExitRepository repository;
  private TravelServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(RoomExitRepository.class);
    service = new TravelServiceImpl(repository);
  }

  @Test
  void findsSimplePath() {
    Room r1 = new Room();
    r1.setId(1L);
    net.firedevops.firemud.entity.Region region = new net.firedevops.firemud.entity.Region();
    region.setSpacingMultiplier(1.0);
    r1.setRegion(region);
    Room r2 = new Room();
    r2.setId(2L);
    r2.setRegion(r1.getRegion());
    Room r3 = new Room();
    r3.setId(3L);
    r3.setRegion(r1.getRegion());
    RoomExit e1 = new RoomExit();
    e1.setFromRoom(r1);
    e1.setToRoom(r2);
    e1.setTenantId(1L);
    RoomExit e2 = new RoomExit();
    e2.setFromRoom(r2);
    e2.setToRoom(r3);
    e2.setTenantId(1L);
    Mockito.when(repository.findByTenantId(1L)).thenReturn(List.of(e1, e2));
    List<Long> path = service.findPath(1L, 1L, 3L);
    assertEquals(List.of(1L, 2L, 3L), path);
  }
}
